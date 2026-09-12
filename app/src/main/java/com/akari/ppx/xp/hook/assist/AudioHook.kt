@file:Suppress("unused", "unchecked_cast", "type_mismatch_warning")

package com.akari.ppx.xp.hook.assist

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.absFeedCellClass
import com.akari.ppx.xp.Init.actionType1
import com.akari.ppx.xp.Init.actionType1Class
import com.akari.ppx.xp.Init.actionType2
import com.akari.ppx.xp.Init.actionType2Class
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.ctx
import com.akari.ppx.xp.Init.downloadVideo
import com.akari.ppx.xp.Init.downConfig
import com.akari.ppx.xp.Init.downListenerClass
import com.akari.ppx.xp.Init.feedCellUtilClass
import com.akari.ppx.xp.Init.feedCellUtilCompanionClass
import com.akari.ppx.xp.Init.getVideoDownload
import com.akari.ppx.xp.Init.hasDownloadVideo
import com.akari.ppx.xp.Init.videoDownloadConfigClass
import com.akari.ppx.xp.Init.videoDownloadHelperClass
import com.akari.ppx.xp.hook.SwitchHook
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy

class AudioHook : SwitchHook("save_audio") {
    companion object {
        @Volatile
        var suppressHostToastUntil: Long = 0L

        fun markAudioSaveStarted() {
            suppressHostToastUntil = SystemClock.uptimeMillis() + 30_000L
        }

        fun shouldSuppressHostToast(): Boolean {
            return SystemClock.uptimeMillis() <= suppressHostToastUntil
        }

        fun clearHostToastSuppression() {
            suppressHostToastUntil = 0L
        }
    }

    override fun onHook() {
        val optionActionTypeClass =
            "com.sup.android.i_sharecontroller.model.OptionAction\$OptionActionType".findClass(cl)
        val actionType = optionActionTypeClass.enumConstants.firstOrNull {
            (it as? Enum<*>)?.name == "ACTION_LIVE_WALLPAPER"
        } ?: return
        hookShareHelperDispatch(actionType, optionActionTypeClass)

        fun HookParam.addOption() {
            val current = result as Array<Any>
            if (current.contains(actionType)) {
                return
            }
            Log.d("AudioHook add option in ${member.declaringClass.name}.${member.name}")
            result = current.plus(actionType)
        }

        actionType1Class!!.hookAfterMethod(actionType1(), absFeedCellClass) { param ->
            param.addOption()
        }
        actionType2Class!!.hookAfterMethod(
            actionType2(),
            absFeedCellClass,
            Boolean::class.java
        ) { param ->
            param.addOption()
        }
        View::class.java.name.hookBeforeMethod(cl, "setTag", Object::class.java) { param ->
            runCatching {
                if (param.args[0] == actionType) {
                    param.thisObject.callMethod("getChildAt", 1)?.callMethod("setText", "保存音频")
                }
            }
        }
        "com.sup.android.m_wallpaper.WallPaperService".replaceMethod(
            cl,
            "setLiveWallpaper",
            Activity::class.java,
            "com.sup.android.mi.feed.repo.bean.cell.AbsFeedCell",
            MutableMap::class.java
        ) { param ->
            Log.i("AudioHook setLiveWallpaper enter")
            startAudioSave(param.args[0] as Activity, param.args[1])
        }
    }

    private fun hookShareHelperDispatch(actionType: Any, optionActionTypeClass: Class<*>) {
        val feedCellClass = absFeedCellClass ?: run {
            Log.e("AudioHook share dispatch hook skipped: absFeedCellClass missing")
            return
        }
        val shareHelperClass = findClassCompat(
            "com.sup.superb.feedui.util.ai",
            "com.sup.superb.feedui.util.ShareHelper"
        )
        val dockerContextClass = findClassCompat("com.sup.superb.dockerbase.misc.DockerContext")
        val baseShareServiceClass = findClassCompat("com.sup.android.i_sharecontroller.IBaseShareService")
        val optionActionStateClass =
            findClassCompat("com.sup.android.i_sharecontroller.model.OptionAction\$b")
        if (
            shareHelperClass == null ||
            dockerContextClass == null ||
            baseShareServiceClass == null ||
            optionActionStateClass == null
        ) {
            Log.e("AudioHook share dispatch hook skipped: class missing")
            return
        }
        val dispatchMethod = shareHelperClass.declaredMethods.firstOrNull { method ->
            method.name == "a" &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            dockerContextClass,
                            feedCellClass,
                            Boolean::class.java,
                            baseShareServiceClass,
                            Activity::class.java,
                            optionActionStateClass,
                            optionActionTypeClass
                        )
                    )
        }
        if (dispatchMethod == null) {
            Log.e("AudioHook share dispatch hook skipped: method missing in ${shareHelperClass.name}")
            return
        }
        Log.i("AudioHook hook share dispatch ${shareHelperClass.name}.${dispatchMethod.name}")
        dispatchMethod.hookBeforeMethod { param ->
            if (param.args.lastOrNull() != actionType) {
                return@hookBeforeMethod
            }
            val activity = param.args[4] as? Activity ?: run {
                Log.e("AudioHook share dispatch skip: activity missing")
                return@hookBeforeMethod
            }
            val absFeedCell = param.args[1] ?: run {
                Log.e("AudioHook share dispatch skip: cell missing")
                return@hookBeforeMethod
            }
            Log.i("AudioHook dispatch share action direct via ${shareHelperClass.name}")
            startAudioSave(activity, absFeedCell)
            param.result = null
        }
    }

    private fun startAudioSave(activity: Activity, absFeedCell: Any?) {
        markAudioSaveStarted()
        val downloadHelperClass = videoDownloadHelperClass!!
        val downloadHelper = downloadHelperClass.getStaticObjectField(
            downloadHelperClass.declaredFields.find { it.type == downloadHelperClass }?.name ?: "b"
        )!!
        val videoModelClass = "com.sup.android.base.model.VideoModel".findClass(cl)
        val downloadConfigClass = videoDownloadConfigClass!!
        val downloadListenerClass =
            "com.ss.android.socialbase.downloader.depend.IDownloadListener".findClass(cl)
        val logCallbackClass = "com.sup.android.video.d".findClass(cl)
        val function1Class = "kotlin.jvm.functions.Function1".findClass(cl)
        val feedCellUtilCompanion = feedCellUtilClass!!.getStaticObjectField(
            feedCellUtilClass!!.declaredFields.find { it.type == feedCellUtilCompanionClass!! }?.name
                ?: "b"
        )
        val videoModel = feedCellUtilCompanion?.callMethod(getVideoDownload(), absFeedCell)
        if (videoModel == null) {
            clearHostToastSuppression()
            Log.i("AudioHook skip: current cell has no video model")
            showStickyToast("视频才能提取音频哦~")
            return
        }
        val downLoadConfig = downloadConfigClass.new()
        downLoadConfig.callMethod("a", -1L)
        runCatching { downLoadConfig.callMethod("c", false) }
        val hasSaved = downloadHelper.callMethodExact(
            hasDownloadVideo(),
            arrayOf(Context::class.java, videoModelClass, String::class.java),
            activity,
            videoModel,
            null
        ) as Boolean
        Log.i("AudioHook start download hasSaved=$hasSaved model=$videoModel")
        val listener = Proxy.newProxyInstance(
            cl,
            arrayOf(downloadListenerClass)
        ) { _, method, args ->
            when (method.name) {
                "onSuccessed" -> {
                    val info = args?.firstOrNull()
                    runCatching {
                        val targetPath = resolveTargetFilePath(info)
                            ?: error("missing target path")
                        extractAudio(targetPath, hasSaved)
                    }.onFailure {
                        clearHostToastSuppression()
                        Log.e("AudioHook extract failed")
                        Log.e(it)
                        showStickyToast("保存失败，请结束皮皮虾进程后重新启动")
                    }
                    null
                }
                "onFailed", "onCanceled" -> {
                    clearHostToastSuppression()
                    Log.i("AudioHook download ${method.name}")
                    showStickyToast("保存失败，请稍后重试")
                    null
                }
                else -> defaultProxyValue(method.returnType)
            }
        }
        downloadHelper.callMethodExact(
            downloadVideo(),
            arrayOf(
                Activity::class.java,
                videoModelClass,
                downloadConfigClass,
                downloadListenerClass,
                logCallbackClass,
                videoModelClass,
                Boolean::class.java,
                function1Class
            ),
            activity,
            videoModel,
            downLoadConfig,
            listener,
            null,
            null,
            false,
            null
        )
    }

    private fun resolveTargetFilePath(downloadInfo: Any?): String? {
        val target = downloadInfo?.callMethodOrNullAs<String>("getTargetFilePath")
        if (!target.isNullOrBlank()) {
            return target
        }
        val savePath = downloadInfo?.callMethodOrNullAs<String>("getSavePath")
        val name = downloadInfo?.callMethodOrNullAs<String>("getName")
        return if (!savePath.isNullOrBlank() && !name.isNullOrBlank()) "$savePath/$name" else null
    }

    private fun extractAudio(videoPath: String, hasSaved: Boolean) {
        val videoFile = File(videoPath)
        require(videoFile.exists()) { "video file missing: $videoPath" }
        val relativeDir = buildAudioRelativeDir()
        val displayName = "${System.currentTimeMillis() / 1000}.m4a"
        val mediaUri = createAudioMediaUri(displayName, relativeDir)
        runCatching {
            val audioTrack = MovieCreator.build(videoPath).tracks.firstOrNull { it.handler == "soun" }
                ?: error("audio track missing")
            openAudioOutputStream(mediaUri).use { fos ->
                Movie().also { movie ->
                    movie.addTrack(audioTrack)
                }.let(DefaultMp4Builder()::build).writeContainer(fos.channel)
            }
            finalizeAudioMediaUri(mediaUri)
            hasSaved.check(false) { videoFile.delete() }
            showStickyToast("已保存至Music/$relativeDir")
            Log.i("AudioHook saved audio=$mediaUri video=$videoPath")
        }.getOrElse {
            clearHostToastSuppression()
            runCatching { deleteMediaUri(mediaUri) }
            throw it
        }
    }

    private fun buildAudioRelativeDir(): String {
        val downloadDir = "com.sup.android.business_utils.config.AppConfig".findClass(cl)
            .callStaticMethodAs<String>("getDownloadDir")
        return "$downloadDir/audio"
    }

    private fun createAudioMediaUri(displayName: String, relativeDir: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val candidates = buildAudioMediaInsertCandidates(relativeDir)
            for ((collectionUri, candidatePath) in candidates) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, candidatePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = runCatching {
                    ctx.contentResolver.insert(collectionUri, values)
                }.onFailure {
                    Log.e("AudioHook media insert failed uri=$collectionUri path=$candidatePath")
                    Log.e(it)
                }.getOrNull()
                if (uri != null) {
                    Log.i("AudioHook media insert ok uri=$uri collection=$collectionUri path=$candidatePath")
                    return uri
                }
            }
            error("insert media store failed")
        }
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            relativeDir
        )
        baseDir.checkIf({ !exists() }) { mkdirs() }
        return Uri.fromFile(File(baseDir, displayName))
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun buildAudioMediaInsertCandidates(relativeDir: String): List<Pair<Uri, String>> {
        val preferredAudioPath = Environment.DIRECTORY_MUSIC + "/$relativeDir"
        val preferredDownloadPath = Environment.DIRECTORY_DOWNLOADS + "/$relativeDir"
        val audioPrimary = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val downloadPrimary = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return listOf(
            audioPrimary to preferredAudioPath,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to preferredAudioPath,
            downloadPrimary to preferredDownloadPath,
            MediaStore.Downloads.EXTERNAL_CONTENT_URI to preferredDownloadPath
        ).distinctBy { "${it.first}|${it.second}" }
    }

    private fun openAudioOutputStream(uri: Uri): FileOutputStream {
        if ("file".equals(uri.scheme, true)) {
            return FileOutputStream(File(requireNotNull(uri.path) { "file uri path missing" }))
        }
        val resolver = ctx.contentResolver
        val descriptor = resolver.openFileDescriptor(uri, "w")
            ?: error("open media store descriptor failed")
        return ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    }

    private fun finalizeAudioMediaUri(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !"file".equals(uri.scheme, true)) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            ctx.contentResolver.update(uri, values, null, null)
        }
    }

    private fun deleteMediaUri(uri: Uri) {
        if ("file".equals(uri.scheme, true)) {
            File(requireNotNull(uri.path)).delete()
        } else {
            ctx.contentResolver.delete(uri, null, null)
        }
    }

    private fun defaultProxyValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }

    private fun findClassCompat(vararg names: String): Class<*>? {
        for (name in names) {
            val clazz = runCatching { name.findClass(cl) }.getOrNull()
            if (clazz != null) {
                return clazz
            }
        }
        return null
    }
}

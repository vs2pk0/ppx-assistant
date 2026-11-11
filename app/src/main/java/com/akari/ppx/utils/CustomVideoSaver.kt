package com.akari.ppx.utils

import android.app.Activity
import android.app.Application
import android.app.ProgressDialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal fun Context.mediaActivity(): Activity? {
    var context = this
    while (context is ContextWrapper && context !is Activity) context = context.baseContext
    return context as? Activity
}

/** Downloads on a worker, exposes progress immediately and publishes only validated files. */
internal object CustomVideoSaver {
    private val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    fun save(context: Context, source: CleanVideoSource.Source, group: String, onSaved: () -> Unit = {}) {
        val activity = context.mediaActivity() ?: error("当前页面无法保存视频")
        check(!activity.isFinishing && !activity.isDestroyed) { "页面已关闭" }
        check(busy.compareAndSet(false, true)) { "正在保存视频，请稍候" }
        val canceled = AtomicBoolean(false)
        val lock = Any()
        var committed = false
        val connection = AtomicReference<HttpURLConnection?>()
        var worker: Thread? = null
        val progress = ProgressDialog(android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_Light_Dialog_Alert)).apply {
            setTitle("保存自定义视频")
            setMessage("正在解析无水印视频…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = true; max = 100
            setCancelable(true); setCanceledOnTouchOutside(false)
        }
        fun cancel() {
            synchronized(lock) {
                if (committed) return
                canceled.set(true)
            }
            worker?.interrupt()
            thread(name = "piyou-video-cancel") { runCatching { connection.get()?.disconnect() } }
        }
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityDestroyed(target: Activity) { if (target === activity) { cancel(); runCatching { progress.dismiss() } } }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        }
        progress.setOnCancelListener { cancel() }
        progress.setButton(ProgressDialog.BUTTON_NEGATIVE, "取消") { _, _ -> cancel() }
        try {
            progress.show()
            activity.application.registerActivityLifecycleCallbacks(lifecycle)
        } catch (error: Exception) { busy.set(false); throw error }
        worker = thread(start = false, name = "piyou-save-video") {
            var directory: File? = null
            fun checkCanceled() { check(!canceled.get() && !Thread.currentThread().isInterrupted) { "已取消保存" } }
            val result = runCatching {
                checkCanceled()
                val existing = VideoGroups.load().flatMap { it.videos }.firstOrNull { it.id == source.id && File(it.path).isFile }
                val video = existing ?: run {
                    val url = CleanVideoSource.resolve(source.id)
                    checkCanceled()
                    val folder = File(StoragePaths.dir("videos"), UUID.randomUUID().toString()).also {
                        check(it.mkdirs()) { "无法创建视频目录" }; directory = it
                    }
                    val partial = File(folder, "video.mp4.partial")
                    var lastProgress = 0L
                    VideoDownload.save(url, partial, canceled, connection) { total, length, attempt ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (total == 0L || now - lastProgress >= 150L) {
                            lastProgress = now
                            main.post {
                                if (!canceled.get() && progress.isShowing) {
                                    progress.isIndeterminate = length <= 0
                                    if (length > 0) progress.progress = ((total * 100L) / length).toInt().coerceIn(0, 100)
                                    val prefix = if (attempt > 1) "正在重试下载（$attempt/3）" else "正在下载无水印视频"
                                    progress.setMessage(if (length > 0) "$prefix：${mb(total)} / ${mb(length)} MB"
                                        else "$prefix：${mb(total)} MB")
                                }
                            }
                        }
                    }
                    checkCanceled()
                    main.post { if (progress.isShowing) { progress.isIndeterminate = true; progress.setMessage("正在生成视频封面…") } }
                    val file = File(folder, "video.mp4")
                    check(partial.renameTo(file)) { "无法保存视频文件" }
                    inspect(source, file)
                }
                synchronized(lock) {
                    checkCanceled()
                    val added = VideoGroups.add(group, video)
                    check(added || VideoGroups.load().any { it.name == group && it.videos.any { v -> v.id == video.id } }) { "分组已不存在，请重新保存" }
                    committed = true
                }
                video
            }
            if (!committed) directory?.deleteRecursively()
            main.post {
                activity.application.unregisterActivityLifecycleCallbacks(lifecycle)
                busy.set(false); runCatching { progress.dismiss() }
                if (!activity.isFinishing && !activity.isDestroyed && !canceled.get()) result
                    .onSuccess { context.showToast("已保存无水印视频到「$group」"); onSaved() }
                    .onFailure { Log.e(it); context.showToast("保存失败：${it.message ?: "请稍后重试"}") }
                Log.i("CustomVideo save completed=${result.isSuccess} canceled=${canceled.get()}")
            }
        }
        worker!!.start()
    }

    private fun mb(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0)

    private fun inspect(source: CleanVideoSource.Source, file: File): CustomVideo {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            check(duration > 0 && width > 0 && height > 0) { "视频数据无效，请重试" }
            val original = retriever.getFrameAtTime(-1) ?: error("无法读取视频封面")
            val scale = minOf(1f, 320f / maxOf(original.width, original.height))
            val thumbnail = Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1), true)
            val cover = File(file.parentFile, "thumbnail.jpg")
            try { cover.outputStream().use { check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
            finally { if (thumbnail !== original) thumbnail.recycle(); original.recycle() }
            return CustomVideo(source.id, source.title, file.path, cover.path, duration, width, height)
        } finally { retriever.release() }
    }
}

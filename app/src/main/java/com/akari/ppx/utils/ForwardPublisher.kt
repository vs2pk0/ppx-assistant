package com.akari.ppx.utils

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import com.akari.ppx.xp.Init
import java.io.File
import java.io.Serializable
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal object ForwardPublisher {
    const val MARKER = "piyou_forward"
    const val ACTIVITY = "com.sup.android.module.publish.view.PublishActivity"
    private val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    @Suppress("DEPRECATION")
    fun start(context: Context, content: ForwardContent) {
        var base = context
        while (base is ContextWrapper && base !is Activity) base = base.baseContext
        val activity = base as? Activity ?: error("当前页面无法打开发帖页")
        kotlin.check(!activity.isFinishing && !activity.isDestroyed)
        kotlin.check(busy.compareAndSet(false, true)) { "正在准备转发，请稍候" }
        val canceled = AtomicBoolean(false)
        val progress = ProgressDialog(activity).apply {
            setTitle("准备转发"); setMessage("正在获取完整内容…")
            setCancelable(true); setCanceledOnTouchOutside(false)
            setOnCancelListener { canceled.set(true) }
        }
        try { progress.show() } catch (error: Exception) { busy.set(false); throw error }
        thread(name = "piyou-forward") {
            // Keep successful files in filesDir: the host may retain them in a saved draft.
            val directory = File(activity.filesDir, "piyou-forward/${java.util.UUID.randomUUID()}")
            val result = runCatching {
                kotlin.check(directory.mkdirs()) { "无法创建转发文件" }
                val models = content.media.mapIndexed { index, media ->
                    kotlin.check(!canceled.get()) { "已取消转发" }
                    main.post { if (!canceled.get() && !activity.isDestroyed) progress.setMessage("正在下载 ${index + 1}/${content.media.size}") }
                    val file = download(media, directory, index, canceled)
                    val cover = if (media.video && media.coverUrls.isNotEmpty())
                        runCatching { download(ForwardContent.Media(media.coverUrls, false), directory, -1, canceled) }.getOrNull()
                    else null
                    chooser(file, media.video, cover)
                }
                Intent().setClassName(activity.packageName, ACTIVITY).apply {
                    putExtra(MARKER, true)
                    putExtra("content_text", content.text)
                    putExtra("title_text", content.title)
                    putExtra("enter_from", "share")
                    putExtra("source", "piyou_forward")
                    putExtra("publish_type", if (content.media.any { it.video }) 1 else if (models.isNotEmpty()) 2 else 0)
                    putExtra("publish_legal_optimize", true)
                    if (content.media.any { it.video }) {
                        putExtra("video_model", models.single())
                        putExtra("origin_video_path", models.single().callMethod("getFilePath") as String)
                    } else if (models.isNotEmpty()) putExtra("image_models", ArrayList(models))
                }
            }
            main.post {
                busy.set(false)
                runCatching { progress.dismiss() }
                if (canceled.get() || activity.isFinishing || activity.isDestroyed) {
                    thread { directory.deleteRecursively() }
                    return@post
                }
                result.onSuccess { intent ->
                    runCatching { activity.startActivity(intent) }.onFailure {
                        Log.e(it); activity.showToast("无法打开发帖页，请重试")
                        thread { directory.deleteRecursively() }
                    }
                }.onFailure {
                    Log.e(it); activity.showToast("转发失败：${it.message ?: "媒体下载失败，请重试"}")
                    thread { directory.deleteRecursively() }
                }
            }
        }
    }

    private fun download(media: ForwardContent.Media, directory: File, index: Int, canceled: AtomicBoolean): File {
        val file = File(directory, "$index.${if (media.video) "mp4" else "image"}")
        var failure: Throwable? = null
        for (source in media.urls) {
            kotlin.check(!canceled.get()) { "已取消转发" }
            try {
                val url = URL(source)
                kotlin.check(url.protocol in setOf("http", "https")) { "不支持的媒体地址" }
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15000; connection.readTimeout = 30000
                    kotlin.check(connection.responseCode in 200..299) { "下载响应 ${connection.responseCode}" }
                    connection.inputStream.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                kotlin.check(!canceled.get()) { "已取消转发" }
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                kotlin.check(total <= 1024L * 1024 * 1024) { "媒体超过 1GB，暂不支持转发" }
                                output.write(buffer, 0, count)
                            }
                            kotlin.check(total > 0 && (connection.contentLengthLong < 0 || total == connection.contentLengthLong)) { "媒体下载不完整" }
                        }
                    }
                    if (!media.video) {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, options)
                        kotlin.check(options.outWidth > 0 && options.outHeight > 0) { "图片数据无效" }
                        val suffix = when (options.outMimeType) {
                            "image/gif" -> "gif"; "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg"
                        }
                        val renamed = File(directory, "$index.$suffix")
                        kotlin.check(file.renameTo(renamed))
                        return renamed
                    }
                    return file
                } finally { connection.disconnect() }
            } catch (error: Exception) { failure = error; file.delete() }
        }
        throw IllegalStateException("第 ${index + 1} 个媒体下载失败", failure)
    }

    internal fun chooser(file: File, video: Boolean, fallbackCover: File? = null): Serializable {
        val model = "com.ss.android.socialbase.mediamanager.MediaModel".findClass(Init.cl).new(0L)!!
        model.callMethod("setFilePath", file.path)
        model.callMethod("setType", if (video) 1 else 0)
        model.callMethod("setFileSize", file.length())
        if (video) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                kotlin.check(duration > 0) { "视频数据无效" }
                model.callMethod("setDuration", duration)
                model.callMethod("setWidth", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0)
                model.callMethod("setHeight", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0)
                model.callMethod("setMimeType", "video/mp4")
                val cover = File(file.parentFile, "cover.jpg")
                val bitmap = runCatching { retriever.getFrameAtTime(-1) }.getOrNull()
                if (bitmap != null) {
                    try { cover.outputStream().use { kotlin.check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) } }
                    finally { bitmap.recycle() }
                    model.callMethod("setThumbnail", cover.path)
                } else {
                    kotlin.check(fallbackCover != null) { "无法读取视频封面" }
                    model.callMethod("setThumbnail", fallbackCover.path)
                }
            } finally { retriever.release() }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            kotlin.check(options.outWidth > 0)
            model.callMethod("setWidth", options.outWidth); model.callMethod("setHeight", options.outHeight)
            model.callMethod("setMimeType", options.outMimeType ?: "image/jpeg")
            model.callMethod("setThumbnail", file.path)
        }
        return "$ACTIVITY\$OpenSchemaImageChooserModel".findClass(Init.cl).new(model) as Serializable
    }
}

package com.akari.ppx.utils

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

/** Copy original bytes and register their actual format; the host labels even GIF files as JPEG. */
object GallerySaver {
    fun save(activity: Activity, button: View) {
        val images = activity.callMethodOrNullAs<List<*>>("getImages") ?: return
        val index = activity.callMethodOrNull("getVpGallery")?.callMethodOrNullAs<Int>("getCurrentItem") ?: return
        val item = images.getOrNull(index) ?: return
        fun urls(method: String) = item.callMethodOrNullAs<List<*>>(method).orEmpty()
            .mapNotNull { it as? String ?: it.callMethodOrNullAs<String>("getUrl") }.filter { it.isNotBlank() }
        val display = urls("getUrlList")
        val download = urls("getDownloadList")
        val animated = item.callMethodOrNullAs<Boolean>("isGif") == true
        val sources = (if (animated) download + display else display + download).distinct()
        if (sources.isEmpty()) { activity.showToast("没有可保存的图片"); return }
        button.isEnabled = false
        val context = activity.applicationContext
        thread(name = "ppx-image-save") {
            val result = runCatching {
                var failure: Throwable? = null
                var saved: String? = null
                for (source in sources) {
                    try { saved = copyToAlbum(context, source, animated); break }
                    catch (error: Exception) { failure = error }
                }
                saved ?: throw IllegalStateException(
                    if (animated) "未取得完整动图，未保存静态预览，请重新加载后重试" else "图片下载失败，请稍后重试", failure)
            }
            button.post {
                button.isEnabled = true
                result.fold({ context.showToast("已保存到相册 · $it") }, {
                    Log.e(it); context.showToast(it.message ?: "保存失败，请稍后重试")
                })
            }
        }
    }

    private fun copyToAlbum(context: Context, source: String, animated: Boolean): String {
        val temporary = File.createTempFile("ppx-save-", ".image", context.cacheDir)
        try {
            val uri = Uri.parse(source)
            val input = when (uri.scheme) {
                "file", "content" -> context.contentResolver.openInputStream(uri)
                "https", "http" -> URL(source).openConnection().apply {
                    connectTimeout = 15000; readTimeout = 30000
                }.getInputStream()
                else -> error("Unsupported image URI")
            } ?: error("No image data")
            input.use { stream -> temporary.outputStream().use { stream.copyTo(it) } }
            val header = temporary.inputStream().use { stream -> ByteArray(16).also { kotlin.check(stream.read(it) >= 12) } }
            val format = when {
                String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif" to "image/gif"
                header[0] == 0x89.toByte() && String(header, 1, 3, Charsets.US_ASCII) == "PNG" -> "png" to "image/png"
                header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> "jpg" to "image/jpeg"
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp" to "image/webp"
                else -> error("Unsupported image data")
            }
            if (animated) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) error("当前系统无法验证完整动图")
                val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(temporary))
                kotlin.check(drawable is AnimatedImageDrawable) { "下载地址返回了静态图片" }
                drawable.stop()
            }
            val name = "PPX_${System.currentTimeMillis()}.${format.first}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, format.second)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/皮皮虾")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val output = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Album unavailable")
                try {
                    resolver.openOutputStream(output)?.use { stream -> temporary.inputStream().use { it.copyTo(stream) } }
                        ?: error("Cannot write image")
                    resolver.update(output, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (error: Throwable) {
                    resolver.delete(output, null, null)
                    throw error
                }
            } else {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "皮皮虾")
                kotlin.check(directory.isDirectory || directory.mkdirs())
                val output = File(directory, name)
                temporary.copyTo(output)
                android.media.MediaScannerConnection.scanFile(context, arrayOf(output.path), arrayOf(format.second), null)
            }
            return format.first.uppercase()
        } finally {
            temporary.delete()
        }
    }
}

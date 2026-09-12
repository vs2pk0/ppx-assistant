package com.akari.ppx.utils

import android.app.Activity
import android.content.ContentValues
import android.content.Context
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
            .mapNotNull { it.callMethodOrNullAs<String>("getUrl") }.filter { it.isNotBlank() }
        val display = urls("getUrlList")
        val download = urls("getDownloadList")
        val source = if (item.callMethodOrNullAs<Boolean>("isGif") == true) {
            (display + download).firstOrNull { it.contains(".gif", true) || it.contains("format=gif", true) }
                ?: download.firstOrNull() ?: display.firstOrNull()
        } else display.firstOrNull() ?: download.firstOrNull()
        if (source == null) { activity.showToast("没有可保存的图片"); return }
        button.isEnabled = false
        val context = activity.applicationContext
        thread(name = "ppx-image-save") {
            val result = runCatching { copyToAlbum(context, source) }
            button.post {
                button.isEnabled = true
                result.fold({ context.showToast("已保存到相册 · $it") }, {
                    Log.e(it); context.showToast("保存失败，请稍后重试")
                })
            }
        }
    }

    private fun copyToAlbum(context: Context, source: String): String {
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
            val header = temporary.inputStream().use { stream -> ByteArray(16).also { check(stream.read(it) >= 8) } }
            val format = when {
                String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "gif" to "image/gif"
                header[0] == 0x89.toByte() && String(header, 1, 3, Charsets.US_ASCII) == "PNG" -> "png" to "image/png"
                header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() -> "jpg" to "image/jpeg"
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp" to "image/webp"
                else -> error("Unsupported image data")
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
                check(directory.isDirectory || directory.mkdirs())
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

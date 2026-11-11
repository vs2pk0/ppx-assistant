package com.akari.ppx.utils

import android.content.Context
import android.net.Uri
import com.akari.ppx.data.Prefs
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Settings and the actual media bytes, portable between devices. */
object BackupFiles {
    const val SETTINGS_KEY = "backup_settings_json"

    private fun dataRoot(): File {
        // Do not silently fall back to the module private folder and export an empty library.
        val dir = File(StoragePaths.configured())
        check(dir.isDirectory && dir.list() != null && dir.canRead() && dir.canWrite()) {
            "无法访问皮皮虾数据目录，请授予所有文件访问权限并先打开一次皮皮虾"
        }
        return dir
    }

    fun export(context: Context, progress: (String) -> Unit = {}): File {
        val root = dataRoot()
        val prefs = runBlocking { Prefs.dataStore.data.first() }.asMap().mapKeys { it.key.name }
        fun array(name: String): JsonArray = File(root, name).let {
            if (it.exists()) JsonParser.parseString(it.readText()).asJsonArray else JsonArray()
        }
        fun mediaPath(path: String): String = BackupArchive.localMedia(root, path)?.absolutePath
            ?: error("视频或封面不在当前数据目录中")
        val videos = BackupArchive.parseVideos(array("custom_videos.json")).map { group ->
            group.copy(videos = group.videos.map { video ->
                video.copy(path = mediaPath(video.path), cover = mediaPath(video.cover))
            }.toMutableList())
        }
        val data = BackupArchive.Data(prefs, BackupArchive.parseEmojis(array("custom_emoticons.json")), videos)
        val downloads = File(context.cacheDir, "backup-download-${UUID.randomUUID()}")
        val output = File(root, "exports").also { check(it.isDirectory || it.mkdirs()) }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val target = File(output, "皮友助手备份-$stamp-${UUID.randomUUID().toString().take(6)}.zip")
        try {
            BackupArchive.write(target, data, { emoji -> resolveEmoji(emoji, root, downloads, progress) }, progress)
            Log.i("BackupFiles export settings=${BackupArchive.settings(prefs).size} emojis=${data.emoticons.sumOf { it.emojis.size }} videos=${data.videos.sumOf { it.videos.size }} bytes=${target.length()}")
            return target
        } finally { downloads.deleteRecursively() }
    }

    private fun resolveEmoji(emoji: CustomEmoticon, root: File, downloads: File, progress: (String) -> Unit): File {
        emoji.local?.let { BackupArchive.localMedia(root, it) }?.let { return it }
        val local = when {
            emoji.uri.startsWith("/") && !emoji.uri.startsWith("//") -> emoji.uri
            emoji.uri.startsWith("file:") -> Uri.parse(emoji.uri).path ?: error("表情文件路径无效")
            else -> null
        }
        if (local != null) return BackupArchive.localMedia(root, local) ?: error("表情文件不在当前数据目录中")
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(emoji.uri.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
        File(root, "emoticons").listFiles()?.firstNotNullOfOrNull { file ->
            if (file.name.startsWith(hash)) BackupArchive.localMedia(root, file.path) else null
        }?.let { return it }
        val address = when {
            emoji.uri.startsWith("https://") || emoji.uri.startsWith("http://") -> emoji.uri
            emoji.uri.startsWith("//") -> "https:${emoji.uri}"
            else -> "https://p1-ppx.bytecdn.cn/${emoji.uri}"
        }
        progress("正在补全未缓存的表情文件…")
        check(downloads.isDirectory || downloads.mkdirs())
        val file = File(downloads, "$hash.img")
        var last: Exception? = null
        repeat(3) {
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000; connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
                connection.inputStream.use { input -> file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024); var count = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        count += n; check(count <= 50L * 1024 * 1024) { "表情文件超过 50MB" }; out.write(buffer, 0, n)
                    }
                    check(count > 0) { "空表情文件" }
                    if (connection.contentLengthLong >= 0) check(count == connection.contentLengthLong) { "表情下载不完整" }
                } }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "表情文件无法解码" }
                return file
            } catch (error: Exception) { last = error; file.delete() }
            finally { connection.disconnect() }
        }
        error("表情文件补全失败，未生成不完整备份：${last?.message}")
    }

    fun importUri(context: Context, uri: Uri, progress: (String) -> Unit = {}): String {
        val root = dataRoot()
        val prepared = context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            input.mark(4)
            val first = input.read(); val second = input.read(); input.reset()
            if (first == 0x50 && second == 0x4b) BackupArchive.read(input,
                File(context.cacheDir, "backup-import-${UUID.randomUUID()}"), progress)
            else {
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    check(bytes.size() + n <= 8 * 1024 * 1024) { "JSON 备份超过 8MB" }
                    bytes.write(buffer, 0, n)
                }
                BackupArchive.legacy(bytes.toString("UTF-8"))
            }
        } ?: error("无法读取备份文件")
        return prepared.use {
            progress("校验通过，正在恢复设置和分组…")
            BackupArchive.restore(it, root, Prefs::importBackup)
            val data = it.data
            (data.settings["hide_icon"] as? Boolean)?.let { hidden -> hideIcon(context, hidden) }
            val settings = data.settings.size
            val emojis = data.emoticons.sumOf { g -> g.emojis.size }
            val videos = data.videos.sumOf { g -> g.videos.size }
            Log.i("BackupFiles import settings=$settings emojis=$emojis videos=$videos portable=${it.portable}")
            "已导入 $settings 项模块设置、$emojis 张表情、$videos 个视频；重启皮皮虾生效" +
                if (it.portable) "" else "。旧 JSON 不含媒体文件，表情需联网加载"
        }
    }

    fun sharedRoot(): String = StoragePaths.configured()
    fun exportDir(): String = File(StoragePaths.configured(), "exports").absolutePath
}

package com.akari.ppx.utils

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/** Resolve the original playback stream; never fall back to a watermarked download. */
internal object CleanVideoSource {
    data class Source(val id: String, val title: String)

    fun from(cell: Any?): Source? {
        val item = ForwardContent.selected(cell) ?: return null
        val model = listOf("getOriginDownloadVideoModel", "getVideoDownload", "getVideoDownloadInfo", "getVideoHigh", "getVideoInfo")
            .firstNotNullOfOrNull { item.callMethodOrNull(it) }
        val id = sequenceOf(
            item.callMethodOrNull("getOriginVideoId"), model?.callMethodOrNull("getUri"), item.callMethodOrNull("getVideoId")
        ).filterIsInstance<String>().mapNotNull(::videoId).firstOrNull() ?: return null
        val title = (item.callMethodOrNull("getText") ?: item.callMethodOrNull("getContent") ?: item.callMethodOrNull("getTitle"))
            ?.toString().orEmpty().trim().ifBlank { "未命名视频" }.take(100)
        return Source(id, title)
    }

    internal fun videoId(value: String): String? {
        val candidate = if (value.startsWith("http")) value.substringBefore('?').substringBefore('#')
            .substringAfter("/mp4/", "").takeIf { '/' !in it } else value
        return candidate?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,128}")) }
    }

    fun resolve(id: String): String {
        require(videoId(id) == id) { "视频标识不可用" }
        val ts = System.currentTimeMillis()
        val sign = MessageDigest.getInstance("MD5").digest(
            "ts${ts}userbdsversion1video${id}vtypemp4f425df23905d4ee38685e276072faa0c".toByteArray()
        ).joinToString("") { "%02x".format(it) }
        val connection = URL("https://i.snssdk.com/video/play/1/bds/$ts/$sign/mp4/$id").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000; connection.readTimeout = 10000
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/123.0 Mobile Safari/537.36")
            check(connection.responseCode in 200..299) { "无水印地址解析失败（${connection.responseCode}）" }
            return decode(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    internal fun decode(json: String): String {
        val videos = JsonParser.parseString(json).asJsonObject.getAsJsonObject("video_info")
            ?.getAsJsonObject("data")?.getAsJsonObject("video_list") ?: error("视频暂无可用的无水印地址")
        for (index in 5 downTo 0) {
            val encoded = videos.getAsJsonObject("video_$index")?.get("main_url")?.asString ?: continue
            val url = runCatching { String(Base64.getDecoder().decode(encoded)) }.getOrNull() ?: continue
            if (url.startsWith("https://") || url.startsWith("http://")) return url
        }
        error("视频暂无可用的无水印地址")
    }
}

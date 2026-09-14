package com.akari.ppx.utils

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL

data class AssistantRelease(val version: String, val notes: String, val url: String)

object AssistantUpdates {
    private const val API = "https://api.github.com/repos/vs2pk0/ppx-assistant/releases/latest"
    const val RELEASES = "https://github.com/vs2pk0/ppx-assistant/releases"

    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String): List<java.math.BigInteger>? {
            val normalized = value.trim().removePrefix("v")
            if (!normalized.matches(Regex("[0-9]+([.][0-9]+)*"))) return null
            return normalized.split('.').map { it.toBigInteger() }
        }
        val next = parts(candidate) ?: return false
        val installed = parts(current) ?: return false
        for (index in 0 until maxOf(next.size, installed.size)) {
            val comparison = (next.getOrNull(index) ?: java.math.BigInteger.ZERO)
                .compareTo(installed.getOrNull(index) ?: java.math.BigInteger.ZERO)
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    fun latest(): AssistantRelease? {
        val connection = URL(API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "Piyou-Assistant")
            if (connection.responseCode == 404) return null
            kotlin.check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            val json = connection.inputStream.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
            if (json.get("draft")?.asBoolean == true || json.get("prerelease")?.asBoolean == true) return null
            val tag = json.get("tag_name").asString
            val body = json.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            return AssistantRelease(tag, body.ifBlank { "此版本暂无更新说明。" },
                "$RELEASES/tag/${java.net.URLEncoder.encode(tag, "UTF-8")}")
        } finally {
            connection.disconnect()
        }
    }
}

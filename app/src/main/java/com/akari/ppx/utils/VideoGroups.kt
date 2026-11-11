package com.akari.ppx.utils

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

internal data class CustomVideo(
    val id: String, val title: String, val path: String, val cover: String,
    val duration: Long, val width: Int, val height: Int
)

internal data class VideoGroup(val name: String, val videos: MutableList<CustomVideo> = mutableListOf())

/** Stable JSON names and one atomic write for changes to both groups. */
internal object VideoGroups {
    private fun file() = File(StoragePaths.root(), "custom_videos.json")

    @Synchronized fun load(): List<VideoGroup> {
        val file = file()
        return if (!file.exists()) emptyList() else decode(file.readText())
    }

    @Synchronized private fun edit(change: (MutableList<VideoGroup>) -> Boolean): Boolean {
        val groups = load().map { it.copy(videos = it.videos.toMutableList()) }.toMutableList()
        if (!change(groups)) return false
        val target = file()
        val payload = encode(groups)
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            java.io.FileOutputStream(temp).use { it.write(payload.toByteArray()); it.fd.sync() }
            check(temp.readText() == payload) { "视频分组写入不完整" }
            check(temp.renameTo(target)) { "无法保存视频分组" }
            return true
        } finally { temp.delete() }
    }

    fun create(name: String): Boolean = edit { groups ->
        if (name.isBlank() || groups.any { it.name == name }) false
        else { groups.add(VideoGroup(name)); true }
    }

    fun rename(from: String, to: String): Boolean = edit { groups ->
        val index = groups.indexOfFirst { it.name == from }
        if (index < 0 || to.isBlank() || groups.any { it.name == to }) false
        else { groups[index] = groups[index].copy(name = to); true }
    }

    fun removeGroup(name: String): Boolean = edit { it.removeAll { group -> group.name == name } }

    fun add(name: String, video: CustomVideo): Boolean = edit { groups ->
        val group = groups.firstOrNull { it.name == name } ?: return@edit false
        if (group.videos.any { it.id == video.id }) false else { group.videos.add(video); true }
    }

    fun remove(name: String, id: String): Boolean = edit { groups ->
        groups.firstOrNull { it.name == name }?.videos?.removeAll { it.id == id } == true
    }

    fun move(from: String, to: String, id: String): Boolean = edit { groups ->
        val moved = moved(groups, from, to, id) ?: return@edit false
        groups.clear(); groups.addAll(moved); true
    }

    internal fun moved(groups: List<VideoGroup>, from: String, to: String, id: String): List<VideoGroup>? {
        if (from == to) return null
        val video = groups.firstOrNull { it.name == from }?.videos?.firstOrNull { it.id == id } ?: return null
        if (groups.none { it.name == to }) return null
        return groups.map { group ->
            val videos = group.videos.toMutableList()
            if (group.name == to && videos.none { it.id == id }) videos.add(video)
            if (group.name == from) videos.removeAll { it.id == id }
            group.copy(videos = videos)
        }
    }

    internal fun encode(groups: List<VideoGroup>): String = JsonArray().apply {
        groups.forEach { group -> add(JsonObject().apply {
            addProperty("name", group.name)
            add("videos", JsonArray().apply { group.videos.forEach { video -> add(JsonObject().apply {
                addProperty("id", video.id); addProperty("title", video.title)
                addProperty("path", video.path); addProperty("cover", video.cover)
                addProperty("duration", video.duration); addProperty("width", video.width); addProperty("height", video.height)
            }) } })
        }) }
    }.toString()

    internal fun decode(json: String): List<VideoGroup> = JsonParser.parseString(json).asJsonArray.map { element ->
        val group = element.asJsonObject
        VideoGroup(group["name"].asString, group["videos"].asJsonArray.map { item ->
            val v = item.asJsonObject
            CustomVideo(v["id"].asString, v["title"].asString, v["path"].asString, v["cover"].asString,
                v["duration"].asLong, v["width"].asInt, v["height"].asInt)
        }.toMutableList())
    }
}

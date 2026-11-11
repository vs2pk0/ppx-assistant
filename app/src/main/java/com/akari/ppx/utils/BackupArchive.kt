package com.akari.ppx.utils

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable media archive. Validation finishes before any live data is changed. */
internal object BackupArchive {
    private const val APP = "皮友助手"
    private const val MANIFEST = "manifest.json"
    private const val MAX_MANIFEST = 8L * 1024 * 1024
    private const val MAX_FILE = 8L * 1024 * 1024 * 1024
    private const val MAX_TOTAL = 50L * 1024 * 1024 * 1024
    private val assetName = Regex("media/[0-9]+\\.[a-z0-9]{1,8}")
    private val localKeys = setOf("storage_dir", "log_tree_uri", "settings_revision",
        "custom_emoticon_groups", "custom_emoticon_groups_rev", "backup_settings_json")

    data class Data(val settings: Map<String, Any>, val emoticons: List<EmoticonGroup>, val videos: List<VideoGroup>)
    class Prepared(
        val data: Data,
        val directory: File?,
        val portable: Boolean,
        val assetDigests: Map<String, String> = emptyMap()
    ) : AutoCloseable {
        override fun close() { directory?.deleteRecursively() }
    }

    fun settings(values: Map<String, Any>): Map<String, Any> = values.filterKeys { it !in localKeys }

    fun write(
        target: File, data: Data, emojiFile: (CustomEmoticon) -> File,
        progress: (String) -> Unit = {}
    ) {
        val manifest = JsonObject().apply {
            addProperty("schema", 2); addProperty("app", APP)
            addProperty("exportedAt", java.time.Instant.now().toString())
            val types = JsonObject()
            add("settings", JsonObject().apply { settings(data.settings).forEach { (key, value) ->
                when (value) {
                    is Boolean -> addProperty(key, value)
                    is String -> addProperty(key, value)
                    is Int -> { addProperty(key, value); types.addProperty(key, "int") }
                    is Long -> { addProperty(key, value); types.addProperty(key, "long") }
                    is Float -> { require(value.isFinite()); addProperty(key, value); types.addProperty(key, "float") }
                    is Set<*> -> add(key, JsonArray().apply { value.forEach { require(it is String); add(it) } })
                    else -> error("不支持的设置类型：$key")
                }
            } })
            add("settingTypes", types)
        }
        val temp = File(target.parentFile, target.name + ".tmp")
        val assets = JsonArray()
        val sources = mutableMapOf<String, String>()
        var archivedBytes = 0L
        val total = data.emoticons.sumOf { it.emojis.size } + data.videos.sumOf { it.videos.size * 2 }
        var done = 0
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
                fun asset(file: File): String {
                    require(file.isFile && file.length() > 0) { "媒体文件缺失：${file.name}" }
                    require(file.length() <= MAX_FILE) { "媒体文件超过 8GB" }
                    val key = file.canonicalPath
                    val cached = sources[key]
                    if (cached != null) return cached
                    require(sources.size < 19_999) { "媒体文件超过 19999 个" }
                    val ext = file.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
                    val name = "media/${sources.size}.$ext"
                    zip.putNextEntry(ZipEntry(name))
                    val digest = MessageDigest.getInstance("SHA-256")
                    val bytes = file.inputStream().use { copy(it, zip, minOf(MAX_FILE, MAX_TOTAL - archivedBytes), digest) }
                    archivedBytes += bytes
                    zip.closeEntry()
                    require(bytes == file.length()) { "媒体文件在备份过程中发生变化，请重试" }
                    assets.add(JsonObject().apply {
                        addProperty("entry", name); addProperty("bytes", bytes); addProperty("sha256", hex(digest.digest()))
                    })
                    sources[key] = name
                    return name
                }
                val emojis = data.emoticons.map { group -> group.copy(emojis = group.emojis.map { emoji ->
                    progress("正在备份媒体 ${++done} / $total")
                    emoji.copy(local = asset(emojiFile(emoji)))
                }.toMutableList()) }
                val videos = data.videos.map { group -> group.copy(videos = group.videos.map { video ->
                    progress("正在备份媒体 ${++done} / $total")
                    val path = asset(File(video.path))
                    progress("正在备份媒体 ${++done} / $total")
                    video.copy(path = path, cover = asset(File(video.cover)))
                }.toMutableList()) }
                manifest.add("emoticons", JsonParser.parseString(EmoticonGroups.encode(emojis)))
                manifest.add("videos", JsonParser.parseString(VideoGroups.encode(videos)))
                manifest.add("assets", assets)
                val bytes = manifest.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_MANIFEST) { "分组数据过大" }
                zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(bytes); zip.closeEntry()
            }
            require(temp.renameTo(target)) { "无法保存备份文件" }
        } finally { temp.delete() }
    }

    /** Supports the old settings/emoticons JSON, without pretending it contains media files. */
    fun legacy(raw: String): Prepared = Prepared(parse(JsonParser.parseString(raw).asJsonObject, false), null, false)

    fun read(input: InputStream, staging: File, progress: (String) -> Unit = {}): Prepared {
        require(staging.mkdirs()) { "无法创建导入临时目录" }
        try {
            val actual = mutableMapOf<String, Pair<Long, String>>()
            var manifest: JsonObject? = null
            var total = 0L
            val seen = mutableSetOf<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && seen.add(entry.name) && seen.size <= 20_000) { "备份包含重复或过多文件" }
                    if (entry.name == MANIFEST) {
                        val bytes = java.io.ByteArrayOutputStream()
                        copy(zip, bytes, MAX_MANIFEST)
                        manifest = JsonParser.parseString(bytes.toString("UTF-8")).asJsonObject
                    } else {
                        require(assetName.matches(entry.name)) { "备份包含非法文件路径" }
                        progress("正在校验媒体 ${actual.size + 1}")
                        val file = File(staging, entry.name)
                        file.parentFile!!.mkdirs()
                        val digest = MessageDigest.getInstance("SHA-256")
                        val size = file.outputStream().buffered().use { copy(zip, it, minOf(MAX_FILE, MAX_TOTAL - total), digest) }
                        total += size
                        actual[entry.name] = size to hex(digest.digest())
                    }
                    zip.closeEntry()
                }
            }
            val root = manifest ?: error("备份缺少清单")
            val data = parse(root, true)
            val expected = root["assets"].asJsonArray
            val names = mutableSetOf<String>()
            expected.forEach { value ->
                val item = value.asJsonObject
                val name = item["entry"].asString
                require(names.add(name)) { "清单含重复媒体" }
                val pair = actual[name] ?: error("备份缺少媒体文件")
                require(pair.first > 0 && pair.first == item["bytes"].asLong && pair.second == item["sha256"].asString) { "媒体校验失败，备份可能已损坏" }
            }
            require(names == actual.keys) { "媒体清单不完整" }
            fun checkAsset(name: String?) { require(name != null && name in names) { "分组引用的媒体缺失" } }
            data.emoticons.forEach { it.emojis.forEach { e -> checkAsset(e.local) } }
            data.videos.forEach { it.videos.forEach { v -> checkAsset(v.path); checkAsset(v.cover) } }
            return Prepared(data, staging, true, actual.mapValues { it.value.second })
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    /** Merge by group and media identity. New media is staged; both manifests roll back on failure. */
    @Synchronized
    fun restore(prepared: Prepared, root: File, applySettings: (Map<String, Any>) -> Unit): Data {
        require(root.isDirectory || root.mkdirs()) { "无法创建数据目录" }
        val mediaDir = File(root, "restored-media")
        val createdFiles = mutableListOf<File>()
        val restoredAssets = mutableMapOf<String, String>()
        var createdMediaDir = false
        val emojiTarget = File(root, "custom_emoticons.json")
        val videoTarget = File(root, "custom_videos.json")
        val before = listOf(emojiTarget, videoTarget).associateWith { if (it.exists()) it.readBytes() else null }
        var touched = false
        try {
            fun local(path: String?): String? {
                if (path == null) return null
                if (!prepared.portable) return localMedia(root, path)?.absolutePath
                return restoredAssets.getOrPut(path) {
                    if (!mediaDir.isDirectory) {
                        check(mediaDir.mkdir()) { "无法创建媒体恢复目录" }
                        createdMediaDir = true
                    }
                    val source = File(prepared.directory, path)
                    val digest = prepared.assetDigests.getValue(path)
                    val target = File(mediaDir, "$digest.${source.extension}")
                    if (!target.isFile || target.length() != source.length() || sha256(target) != digest) {
                        val temp = File.createTempFile("restore-", ".tmp", mediaDir)
                        try {
                            source.copyTo(temp, overwrite = true)
                            val existed = target.exists()
                            check(temp.renameTo(target)) { "无法恢复媒体文件" }
                            if (!existed) createdFiles.add(target)
                        } finally {
                            temp.delete()
                        }
                    }
                    target.absolutePath
                }
            }
            val incoming = prepared.data
            val emojis = incoming.emoticons.map { it.copy(emojis = it.emojis.map { e -> e.copy(local = local(e.local)) }.toMutableList()) }
            val videos = incoming.videos.map { it.copy(videos = it.videos.map { v ->
                v.copy(path = local(v.path) ?: v.path, cover = local(v.cover) ?: v.cover)
            }.toMutableList()) }
            val merged = merge(
                Data(emptyMap(), before[emojiTarget]?.let { parseEmojis(JsonParser.parseString(String(it, Charsets.UTF_8)).asJsonArray) }.orEmpty(),
                    before[videoTarget]?.let { parseVideos(JsonParser.parseString(String(it, Charsets.UTF_8)).asJsonArray) }.orEmpty()),
                Data(incoming.settings, emojis, videos)
            )
            touched = true
            atomic(emojiTarget, EmoticonGroups.encode(merged.emoticons).toByteArray())
            atomic(videoTarget, VideoGroups.encode(merged.videos).toByteArray())
            applySettings(merged.settings)
            return merged
        } catch (error: Throwable) {
            if (touched) before.forEach { (file, bytes) -> if (bytes == null) file.delete() else atomic(file, bytes) }
            createdFiles.forEach { it.delete() }
            if (createdMediaDir) mediaDir.delete()
            throw error
        }
    }

    internal fun merge(current: Data, incoming: Data): Data {
        val emojis = current.emoticons.map { it.copy(emojis = it.emojis.toMutableList()) }.toMutableList()
        incoming.emoticons.forEach { source ->
            val group = emojis.firstOrNull { it.name == source.name } ?: EmoticonGroup(source.name).also(emojis::add)
            source.emojis.forEach { e ->
                val i = group.emojis.indexOfFirst { it.uri == e.uri }
                if (i < 0) group.emojis.add(e)
                else if (!e.local.isNullOrBlank()) group.emojis[i] = e
            }
        }
        val videos = current.videos.map { it.copy(videos = it.videos.toMutableList()) }.toMutableList()
        incoming.videos.forEach { source ->
            val group = videos.firstOrNull { it.name == source.name } ?: VideoGroup(source.name).also(videos::add)
            source.videos.forEach { v ->
                val i = group.videos.indexOfFirst { it.id == v.id }
                if (i < 0) group.videos.add(v) else group.videos[i] = v
            }
        }
        return Data(incoming.settings, emojis, videos)
    }

    private fun parse(root: JsonObject, portable: Boolean): Data {
        require(root["schema"]?.asInt == if (portable) 2 else 1) { "不支持的备份版本" }
        require(root["app"]?.asString == APP) { "不是皮友助手备份" }
        val values = root["settings"]?.asJsonObject ?: error("备份缺少设置")
        val types = root["settingTypes"]?.asJsonObject
        val settings = linkedMapOf<String, Any>()
        values.entrySet().forEach { (key, value) ->
            require(key.isNotBlank()) { "无效设置名称" }
            if (key !in localKeys) settings[key] = when {
                value.isJsonPrimitive -> value.asJsonPrimitive.let { p -> when {
                    p.isBoolean -> p.asBoolean
                    p.isString -> p.asString
                    p.isNumber -> when (types?.get(key)?.asString) {
                        "int" -> p.asBigDecimal.intValueExact()
                        "long" -> p.asBigDecimal.longValueExact()
                        "float" -> p.asFloat.also { require(it.isFinite()) }
                        // Old backups did not record numeric types. All old UI values were strings or booleans.
                        null -> p.asString
                        else -> error("不支持的设置类型")
                    }
                    else -> error("无效设置")
                } }
                value.isJsonArray -> value.asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }.toSet()
                else -> error("无效设置类型：$key")
            }
        }
        val emojis = root["emoticons"]?.takeUnless { it.isJsonNull }?.asJsonArray ?: JsonArray()
        val videos = if (portable) root["videos"]?.asJsonArray ?: error("缺少视频清单") else JsonArray()
        return Data(settings, parseEmojis(emojis), parseVideos(videos))
    }

    internal fun parseEmojis(array: JsonArray): List<EmoticonGroup> {
        array.forEach { element ->
            val g = element.asJsonObject
            require((g["name"] ?: g["a"])?.asString?.isNotBlank() == true) { "无效表情分组" }
            (g["emojis"] ?: g["b"]).asJsonArray.forEach { item ->
                val e = item.asJsonObject
                require((e["uri"] ?: e["a"])?.asString?.isNotBlank() == true) { "表情缺少来源" }
                // Validate optional fields before the forgiving legacy decoder sees them.
                listOf("w", "h", "b", "c").forEach { e[it]?.asInt }
                (e["gif"] ?: e["d"])?.asBoolean
                e["local"]?.takeUnless { it.isJsonNull }?.asString
            }
        }
        val parsed = EmoticonGroups.decode(array.toString())
        require(parsed.size == array.size()) { "表情清单解析失败" }
        return parsed
    }

    internal fun parseVideos(array: JsonArray): List<VideoGroup> = VideoGroups.decode(array.toString()).also { groups ->
        groups.forEach { g -> require(g.name.isNotBlank()); g.videos.forEach { v ->
            require(v.id.isNotBlank() && v.path.isNotBlank() && v.cover.isNotBlank() && v.duration >= 0 && v.width >= 0 && v.height >= 0) { "无效视频清单" }
        } }
    }

    private fun atomic(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, file.name + ".restore-tmp")
        try {
            java.io.FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            check(temp.renameTo(file)) { "无法保存分组文件" }
        } finally { temp.delete() }
    }

    private fun copy(input: InputStream, out: OutputStream?, limit: Long, digest: MessageDigest? = null): Long {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "备份文件超过大小限制" }
            out?.write(buffer, 0, count); digest?.update(buffer, 0, count)
        }
        return total
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { copy(it, null, MAX_FILE, digest) }
        return hex(digest.digest())
    }

    internal fun localMedia(root: File, path: String): File? = runCatching {
        val file = File(path).canonicalFile
        file.takeIf { it.toPath().startsWith(root.canonicalFile.toPath()) && it.isFile && it.length() > 0 }
    }.getOrNull()
}

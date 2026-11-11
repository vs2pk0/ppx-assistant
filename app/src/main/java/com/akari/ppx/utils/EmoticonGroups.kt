package com.akari.ppx.utils

import com.akari.ppx.data.XPrefs
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

data class CustomEmoticon(
    val uri: String,
    val w: Int = 0,
    val h: Int = 0,
    val gif: Boolean = false,
    /** Cached copy inside the host's own emoticon folder, when available. */
    val local: String? = null
)

data class EmoticonGroup(
    val name: String,
    val emojis: MutableList<CustomEmoticon> = mutableListOf()
)

/**
 * Module-side custom emoticon groups. Entries reference the SOURCE uri so the
 * storage survives export/import: insertion re-collects on demand through the
 * host's own EmojiService (which dedupes by uri).
 */
object EmoticonGroups {
    const val KEY = "custom_emoticon_groups"
    const val REV_KEY = "custom_emoticon_groups_rev"
    private const val FILE_NAME = "custom_emoticons.json"
    private const val REV_FILE = "custom_emoticons.rev"
    private val gson = Gson()
    private var cache: MutableList<EmoticonGroup>? = null

    /**
     * Groups live in the user-visible shared directory
     * (Android/media/&lt;host&gt;/皮友助手/custom_emoticons.json) so they survive a
     * restart and can be copied off the device. The framework's remote
     * preferences are a read-only snapshot for hooked processes, so they are
     * only used as a legacy import source.
     */
    private fun storeFile(): File? = runCatching {
        File(StoragePaths.root(), FILE_NAME)
    }.getOrNull()

    private fun legacyFile(): File? = runCatching {
        val ctx = runCatching { com.akari.ppx.xp.Init.ctx }.getOrNull() ?: return null
        File(ctx.filesDir, "ppx_custom_emoticons.json")
    }.getOrNull()

    /** Read priority: shared media file, previous host-private files, legacy key. */
    private fun readJson(): String {
        val shared = runCatching {
            storeFile()?.takeIf { it.isFile }?.readText() ?: ""
        }.getOrDefault("")
        if (shared.isNotBlank()) {
            Log.i("EmoticonGroups load source=shared bytes=${shared.length}")
            return shared
        }
        val previous = runCatching {
            legacyFile()?.takeIf { it.isFile }?.readText() ?: ""
        }.getOrDefault("")
        if (previous.isNotBlank()) {
            Log.i("EmoticonGroups load source=host files bytes=${previous.length}")
            writeJson(previous)
            return previous
        }
        val legacy = runCatching { XPrefs<String>(KEY, "") }.getOrDefault("")
        if (legacy.isNotBlank()) {
            Log.i("EmoticonGroups load source=legacy bytes=${legacy.length}")
            writeJson(legacy)
        }
        return legacy
    }

    /**
     * Imports pushed from the module UI. The module cannot write the host's
     * media directory, so it stores the payload plus a revision; on the next
     * host start any newer revision is merged into the shared file once.
     */
    private fun applyModuleImport(current: MutableList<EmoticonGroup>): MutableList<EmoticonGroup> {
        val remoteRev = runCatching { XPrefs<String>(REV_KEY, "") }.getOrDefault("")
        if (remoteRev.isBlank()) return current
        val applied = runCatching { revFile()?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
        if (remoteRev == applied) return current
        val payload = runCatching { XPrefs<String>(KEY, "") }.getOrDefault("")
        if (payload.isBlank()) return current
        val imported = decode(payload)
        if (imported.isEmpty()) return current
        val added = merge(current, imported)
        save(current)
        runCatching { revFile()?.writeText(remoteRev) }.onFailure(Log::e)
        Log.i("EmoticonGroups applied module import groups=$added rev=$remoteRev")
        return current
    }

    private fun revFile(): File? = storeFile()?.let { File(it.parentFile, REV_FILE) }

    @Synchronized
    fun load(): MutableList<EmoticonGroup> {
        cache?.let { return it }
        return readStored().also { cache = it }
    }

    /** Reads from disk, ignoring the in-memory cache. */
    private fun readStored(): MutableList<EmoticonGroup> {
        val json = readJson()
        val groups = decode(json)
        if (groups.isEmpty()) return applyModuleImport(mutableListOf())
        // Rewrite unless the file already uses the stable key names, so old
        // obfuscated (a/b/c) files are migrated exactly once.
        if (json.contains("\"a\":") && !json.contains("\"emojis\":")) {
            writeJson(encode(groups))
            Log.i("EmoticonGroups migrated legacy groups=${groups.size}")
        }
        return applyModuleImport(groups)
    }

    /**
     * JSON is built by hand on purpose: R8 strips @SerializedName and renames
     * data-class properties on every release, which used to turn the on-disk
     * format into `a`/`b`/`c` and made groups disappear after an update.
     * Manual mapping keeps the file readable forever and still accepts the
     * legacy obfuscated keys.
     */
    internal fun encode(groups: List<EmoticonGroup>): String {
        val root = JsonArray()
        groups.forEach { group ->
            val item = JsonObject()
            item.addProperty("name", group.name)
            val emojis = JsonArray()
            group.emojis.forEach { emoji ->
                val entry = JsonObject()
                entry.addProperty("uri", emoji.uri)
                entry.addProperty("w", emoji.w)
                entry.addProperty("h", emoji.h)
                entry.addProperty("gif", emoji.gif)
                emoji.local?.let { entry.addProperty("local", it) }
                emojis.add(entry)
            }
            item.add("emojis", emojis)
            root.add(item)
        }
        return gson.toJson(root)
    }

    internal fun decode(json: String): MutableList<EmoticonGroup> = runCatching {
        val root = JsonParser.parseString(json.ifBlank { "[]" }).asJsonArray
        root.mapNotNull { element ->
            val group = element.asJsonObject
            val name = group.stringOf("name", "a") ?: return@mapNotNull null
            val emojis = (group.get("emojis") ?: group.get("b"))
                ?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { item ->
                    val entry = item.asJsonObject
                    val uri = entry.stringOf("uri", "a") ?: return@mapNotNull null
                    CustomEmoticon(
                        uri = uri,
                        w = entry.intOf("w", "b"),
                        h = entry.intOf("h", "c"),
                        gif = entry.boolOf("gif", "d"),
                        local = entry.stringOf("local", null)
                    )
                }.orEmpty().toMutableList()
            EmoticonGroup(name, emojis)
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun JsonObject.stringOf(primary: String, legacy: String?): String? =
        get(primary)?.takeIf { it.isJsonPrimitive }?.asString
            ?: legacy?.let { key -> get(key)?.takeIf { it.isJsonPrimitive }?.asString }

    private fun JsonObject.intOf(primary: String, legacy: String?): Int =
        get(primary)?.takeIf { it.isJsonPrimitive }?.asInt
            ?: legacy?.let { key -> get(key)?.takeIf { it.isJsonPrimitive }?.asInt } ?: 0

    private fun JsonObject.boolOf(primary: String, legacy: String?): Boolean =
        get(primary)?.takeIf { it.isJsonPrimitive }?.asBoolean
            ?: legacy?.let { key -> get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean } ?: false

    /** True when [uri] is present in [groupName] after re-reading storage. */
    @Synchronized
    fun isPersisted(groupName: String, uri: String): Boolean = runCatching {
        val groups = readStored()
        groups.firstOrNull { it.name == groupName }?.emojis?.any { it.uri == uri } == true
    }.getOrDefault(false)

    @Synchronized
    fun isGroupPersisted(name: String): Boolean = runCatching {
        readStored().any { it.name == name }
    }.getOrDefault(false)

    /** Records the on-disk copy of a group entry so it renders without network. */
    @Synchronized
    fun setLocalPath(groupName: String, uri: String, localPath: String?) {
        if (localPath.isNullOrBlank()) return
        val groups = load()
        val target = groups.firstOrNull { it.name == groupName } ?: return
        val index = target.emojis.indexOfFirst { it.uri == uri }
        if (index < 0 || target.emojis[index].local == localPath) return
        target.emojis[index] = target.emojis[index].copy(local = localPath)
        save(groups)
    }

    @Synchronized
    fun save(groups: List<EmoticonGroup>): Boolean {
        if (!writeJson(encode(groups))) return false
        cache = groups.toMutableList()
        return true
    }

    /**
     * Persist synchronously to the shared media file and verify by reading it
     * back. A rename keeps the write atomic so a force-stop cannot truncate it.
     */
    private fun writeJson(json: String): Boolean {
        val target = storeFile()
        if (target == null) {
            Log.e("EmoticonGroups save failed: no storage path")
            return false
        }
        val fileOk = runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                target.writeText(json)
                tmp.delete()
            }
            target.isFile && target.length() > 0
        }.onFailure(Log::e).getOrDefault(false)
        val verified = fileOk && readBackMatches(json)
        Log.i("EmoticonGroups save bytes=${json.length} path=$target ok=$verified")
        return verified
    }

    /** Confirms the value can be read back, which catches silent write failures. */
    private fun readBackMatches(json: String): Boolean = runCatching {
        storeFile()?.takeIf { it.isFile }?.readText() == json
    }.getOrDefault(false)

    /** Absolute path shown in the module UI. */
    fun storePath(): String = storeFile()?.absolutePath ?: StoragePaths.root().absolutePath + "/" + FILE_NAME

    @Synchronized
    fun export(): String = encode(load())

    /** Merges imported groups by name and emoji uri; returns the added count. */
    @Synchronized
    fun import(json: String): Int {
        val parsed = decode(json)
        if (parsed.isEmpty()) return -1
        val imported = parsed
        val groups = load()
        val added = merge(groups, imported)
        save(groups)
        return added
    }

    /** Pure merge used by [import]; exposed for unit tests. */
    fun merge(target: MutableList<EmoticonGroup>, incoming: List<EmoticonGroup?>): Int {
        var added = 0
        incoming.filterNotNull().forEach { source ->
            if (source.name.isNullOrBlank()) return@forEach
            val group = target.firstOrNull { it.name == source.name }
                ?: EmoticonGroup(source.name, mutableListOf()).also(target::add)
            val indices = group.emojis.mapIndexed { index, emoji -> emoji.uri to index }.toMap().toMutableMap()
            source.emojis?.filterNotNull()?.forEach { emoji ->
                if (emoji.uri.isBlank()) return@forEach
                val index = indices[emoji.uri]
                if (index == null) {
                    indices[emoji.uri] = group.emojis.size
                    group.emojis.add(emoji)
                    added++
                } else if (group.emojis[index].local.isNullOrBlank() && !emoji.local.isNullOrBlank()) {
                    group.emojis[index] = group.emojis[index].copy(local = emoji.local)
                }
            }
        }
        return added
    }

    /** One verified write for a bulk import; failed writes leave the cached groups intact. */
    @Synchronized
    fun addEmojis(groupName: String, emojis: List<CustomEmoticon>): Int {
        if (groupName.isBlank()) return -1
        if (emojis.isEmpty()) return 0
        val groups = load().map { it.copy(emojis = it.emojis.toMutableList()) }.toMutableList()
        val added = merge(groups, listOf(EmoticonGroup(groupName, emojis.toMutableList())))
        return if (save(groups)) added else -1
    }

    /** Clears custom entries only, keeping named groups and the host's official favorites. */
    @Synchronized
    fun clearEmojis(): Boolean = save(load().map { it.copy(emojis = mutableListOf()) })

    /** Writes both sides together; a failed write leaves the original cached groups intact. */
    @Synchronized
    fun moveEmoji(sourceName: String, targetName: String, uri: String): Boolean {
        val moved = movedGroups(load(), sourceName, targetName, uri) ?: return false
        return save(moved)
    }

    /** Detached snapshot so removing from the source cannot precede saving the destination. */
    internal fun movedGroups(
        groups: List<EmoticonGroup>, sourceName: String, targetName: String, uri: String
    ): List<EmoticonGroup>? {
        if (sourceName == targetName || uri.isBlank()) return null
        val source = groups.firstOrNull { it.name == sourceName } ?: return null
        if (groups.none { it.name == targetName }) return null
        val emoji = source.emojis.firstOrNull { it.uri == uri } ?: return null
        val moved = groups.map { it.copy(emojis = it.emojis.toMutableList()) }.toMutableList()
        merge(moved, listOf(EmoticonGroup(targetName, mutableListOf(emoji))))
        moved.first { it.name == sourceName }.emojis.removeAll { it.uri == uri }
        return moved
    }

    @Synchronized
    fun addEmoji(groupName: String, emoji: CustomEmoticon): Boolean {
        val groups = load()
        val target = groups.firstOrNull { it.name == groupName }
            ?: EmoticonGroup(groupName, mutableListOf()).also(groups::add)
        val existing = target.emojis.indexOfFirst { it.uri == emoji.uri }
        if (existing >= 0) {
            // Enrich an older entry that only stored the remote uri.
            if (emoji.local.isNullOrBlank() || !target.emojis[existing].local.isNullOrBlank()) return false
            target.emojis[existing] = target.emojis[existing].copy(local = emoji.local)
        } else {
            target.emojis.add(emoji)
        }
        save(groups)
        return true
    }

    @Synchronized
    fun removeEmoji(groupName: String, uri: String): Boolean {
        val groups = load()
        val target = groups.firstOrNull { it.name == groupName } ?: return false
        val removed = target.emojis.removeAll { it.uri == uri }
        if (target.emojis.isEmpty()) groups.remove(target)
        if (removed) save(groups)
        return removed
    }

    @Synchronized
    fun createGroup(name: String): Boolean {
        if (name.isBlank() || load().any { it.name == name }) return false
        val groups = load()
        groups.add(EmoticonGroup(name, mutableListOf()))
        save(groups)
        return true
    }

    @Synchronized
    fun deleteGroup(name: String): Boolean {
        val groups = load()
        val removed = groups.removeAll { it.name == name }
        if (removed) save(groups)
        return removed
    }

    @Synchronized
    fun renameGroup(old: String, new: String): Boolean {
        if (new.isBlank() || load().any { it.name == new }) return false
        val groups = load()
        val target = groups.firstOrNull { it.name == old } ?: return false
        groups.add(groups.indexOf(target), EmoticonGroup(new, target.emojis))
        groups.remove(target)
        save(groups)
        return true
    }
}

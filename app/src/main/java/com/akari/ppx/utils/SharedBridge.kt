package com.akari.ppx.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.akari.ppx.data.Const
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.FileOutputStream

/**
 * File-based channel between the hooked 皮皮虾 process and the module app.
 *
 * Both sides can reach the host's `Android/media` directory without any runtime
 * permission, while Binder/provider calls are subject to Android 11+ package
 * visibility ("Unknown authority"). Settings are already mirrored there as
 * `settings.json`, so the host can read the real configuration even when the
 * framework's remote preferences are unavailable; log lines take the same path.
 */
object SharedBridge {
    private const val SETTINGS_FILE = "settings.json"
    private const val LOG_DIR = "logs"
    private const val HOST_LOG = "host.log"
    private const val MAX_LOG_BYTES = 1_500_000L

    /**
     * The shared folder is always the HOST's media directory:
     * `/storage/emulated/0/Android/media/com.sup.android.superb/皮友助手`.
     *
     * Only the host process can create it (it owns the media_rw directory).
     * The module UI can access it only with "所有文件访问" (MANAGE_EXTERNAL_STORAGE),
     * so it must never use its own `externalMediaDirs`.
     */
    fun root(context: Context?): File? = runCatching {
        val host = runCatching { com.akari.ppx.xp.Init.ctx }.getOrNull()
        if (host != null) {
            val media = host.externalMediaDirs?.firstOrNull() ?: return null
            File(media, Const.APP_NAME).takeIf { it.isDirectory || it.mkdirs() }
        } else {
            File(Const.SHARED_ROOT).takeIf { it.isDirectory }
        }
    }.getOrNull()

    /** True when host log lines can actually be read (SAF grant or all-files access). */
    fun readable(context: Context?): Boolean = runCatching {
        if (treeUri(context) != null && childIdOf(context, "logs") != null) return true
        val dir = root(context) ?: return false
        dir.isDirectory && dir.canRead() && dir.list() != null
    }.getOrDefault(false)

    private fun childIdOf(context: Context?, name: String): String? = runCatching {
        val ctx = context ?: return null
        val tree = treeUri(ctx) ?: return null
        childId(ctx, tree, name)
    }.getOrNull()

    fun settingsFile(context: Context?): File? = root(context)?.let { File(it, SETTINGS_FILE) }

    /**
     * Reads one setting from the mirrored JSON. Returns null when the mirror is
     * missing or does not contain the key, so callers can keep their default.
     */
    fun readSetting(context: Context?, key: String, default: Any): Any? = runCatching {
        val file = settingsFile(context) ?: return null
        if (!file.isFile || file.length() == 0L) return null
        val json = JsonParser.parseString(file.readText())
        if (!json.isJsonObject) return null
        val element = json.asJsonObject.get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        if (default is Boolean) primitive.asBoolean else primitive.asString
    }.getOrNull()

    fun hostLogFile(context: Context?): File? = root(context)?.let { File(File(it, LOG_DIR), HOST_LOG) }

    @Synchronized
    fun appendHostLog(context: Context?, lines: List<String>) {
        if (lines.isEmpty()) return
        val target = hostLogFile(context) ?: return
        runCatching {
            target.parentFile?.mkdirs()
            if (target.length() > MAX_LOG_BYTES) {
                val rotated = File(target.parentFile, "$HOST_LOG.1")
                runCatching { rotated.delete() }
                target.renameTo(rotated)
            }
            FileOutputStream(target, true).bufferedWriter().use { writer ->
                lines.forEach { writer.appendLine(it) }
            }
        }.onFailure { android.util.Log.w("PPXPPX", "SharedBridge log write: ${it.message}") }
    }

    /**
     * Reads the host log. Resolution order:
     *  1. SAF directory the user granted once (no dangerous permission needed).
     *  2. Direct file access, which requires "所有文件访问".
     */
    fun readHostLog(context: Context?, limit: Int): List<String> {
        safHostLog(context, limit)?.let { return it }
        return runCatching {
            val file = hostLogFile(context)
            if (file == null) {
                Log.w("SharedBridge host log path unavailable")
                return emptyList()
            }
            if (!file.isFile) {
                Log.w("SharedBridge host log missing: ${file.absolutePath}")
                return emptyList()
            }
            file.readLines().takeLast(limit)
        }.onFailure { Log.w("SharedBridge host log read failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    fun clearHostLog(context: Context?) {
        runCatching { hostLogFile(context)?.writeText("") }
    }

    /** Persisted SAF tree for the shared folder, e.g. the host's 皮友助手 dir. */
    fun treeUri(context: Context?): Uri? = runCatching {
        val ctx = context ?: return null
        val raw = com.akari.ppx.data.Prefs.get<String>(TREE_KEY, "").orEmpty()
        if (raw.isBlank()) return null
        val uri = Uri.parse(raw)
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        uri
    }.getOrNull()

    fun saveTreeUri(context: Context?, uri: Uri) {
        runCatching {
            context?.contentResolver?.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        com.akari.ppx.data.Prefs.set(TREE_KEY, uri.toString())
    }

    private fun childId(context: Context, parent: Uri, name: String): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, docId)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) return@use cursor.getString(0)
            }
            null
        }
    }.getOrNull()

    private fun safHostLog(context: Context?, limit: Int): List<String>? = runCatching {
        val ctx = context ?: return null
        val tree = treeUri(ctx) ?: return null
        val logDir = childId(ctx, tree, LOG_DIR) ?: return null
        val hostId = childId(
            ctx, DocumentsContract.buildDocumentUriUsingTree(tree, logDir), HOST_LOG
        ) ?: return null
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, hostId)
        ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            reader.readLines().takeLast(limit)
        }
    }.onFailure { Log.w("SharedBridge SAF read failed: ${it.message}") }.getOrNull()

    const val TREE_KEY = "log_tree_uri"

    /** Debug summary used by the log screen header. */
    fun describe(context: Context?): String {
        val file = settingsFile(context)
        val log = hostLogFile(context)
        return "settings=${file?.absolutePath ?: "n/a"}(${file?.length() ?: 0}B) " +
                "hostLog=${log?.let { "${it.length()}B" } ?: "n/a"}"
    }
}

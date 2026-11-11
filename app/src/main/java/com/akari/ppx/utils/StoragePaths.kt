package com.akari.ppx.utils

import android.content.Context
import com.akari.ppx.data.Const
import com.akari.ppx.data.Prefs
import com.akari.ppx.data.XPrefs
import com.akari.ppx.xp.Init
import java.io.File
import java.io.FileOutputStream

/**
 * Shared data root for the host process (hooked 皮皮虾) and the module UI.
 *
 * Default: /storage/emulated/0/Android/media/<host package>/<module name>
 * The host process can write its own Android/media directory without runtime
 * permissions, so settings, cached emoticon files and exported JSON all live in
 * a user-visible folder. The module UI can override the path in its settings;
 * when the module cannot write there (scoped storage) it falls back to its own
 * private files dir and reports that via [lastFallback].
 */
object StoragePaths {
    const val KEY = "storage_dir"
    const val DEFAULT_ROOT =
        "/storage/emulated/0/Android/media/${Const.TARGET_APP_ID}/${Const.APP_NAME}"

    @Volatile
    var lastFallback: String? = null
        private set

    private fun hostContext(): Context? = runCatching { Init.ctx }.getOrNull()

    /** Settings are read through remote prefs in the host, DataStore in the module. */
    fun configured(): String = runCatching {
        val host = hostContext()
        val raw = if (host != null) XPrefs<String>(KEY, "") else Prefs.get<String>(KEY, "")
        normalize(raw)
    }.getOrDefault(DEFAULT_ROOT)

    fun normalize(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return DEFAULT_ROOT
        if (value.startsWith("/")) return value.trimEnd('/')
        return "${DEFAULT_ROOT.substringBeforeLast('/')}/$value".trimEnd('/')
    }

    /** Module UI context; only resolved when the hook context is absent. */
    private fun appContext(): Context? = hostContext() ?: runCatching {
        com.akari.ppx.App.context
    }.getOrNull()

    /**
     * Host-owned external media dir reported by the framework, e.g.
     * /storage/emulated/0/Android/media/com.sup.android.superb. Apps cannot
     * mkdir() under Android/media directly (it is media_rw), but the framework
     * creates this per-package directory with the correct owner/group.
     */
    private fun externalMediaRoot(): File? = runCatching {
        // Only the hooked host process owns a writable Android/media folder;
        // in the module UI this would point at the module's own package dir.
        val ctx = hostContext() ?: return null
        ctx.externalMediaDirs?.firstOrNull()?.let { File(it, Const.APP_NAME) }
    }.getOrNull()

    fun root(): File {
        val desired = File(configured())
        if (ensureWritable(desired)) {
            lastFallback = null
            return desired
        }
        // `configured()` may point at a package name that is not installed, or
        // the user picked an unwritable path; retry through the framework dir.
        val external = externalMediaRoot()
        if (external != null && external != desired && ensureWritable(external)) {
            Log.i("StoragePaths use externalMediaDir=${external.absolutePath}")
            lastFallback = null
            return external
        }
        // Module UI: its own private dir always works. The host reads settings
        // through remote preferences, not this folder.
        val fallback = File(appContext()?.filesDir ?: File("/data/data"), "ppx-data")
        ensureWritable(fallback)
        lastFallback = fallback.absolutePath
        Log.i("StoragePaths fallback=${fallback.absolutePath} desired=${desired.absolutePath}")
        return fallback
    }

    fun dir(vararg parts: String): File {
        var current = root()
        parts.forEach { current = File(current, it) }
        ensureWritable(current)
        return current
    }

    fun emoticonDir(): File = dir("emoticons")

    fun exportDir(): File = dir("exports")

    private fun ensureWritable(file: File): Boolean = runCatching {
        if (!file.exists() && !file.mkdirs() && !file.isDirectory) return false
        if (!file.isDirectory) return false
        val probe = File(file, ".ppx-write-test")
        FileOutputStream(probe).use { it.write(1); it.fd.sync() }
        probe.delete()
        true
    }.getOrDefault(false)

    /** Copies an on-disk emoticon into the shared dir so it renders offline. */
    fun cacheEmoticon(sourcePath: String?, uri: String): String? {
        if (sourcePath.isNullOrBlank()) return null
        val source = File(sourcePath)
        if (!source.isFile || source.length() <= 0L) return null
        val ext = source.name.substringAfterLast('.', "png")
            .lowercase()
            .takeIf { it in setOf("png", "jpg", "jpeg", "gif", "webp") } ?: "png"
        val target = File(emoticonDir(), "${hash(uri)}.$ext")
        if (target.isFile && target.length() == source.length()) return target.absolutePath
        return runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            source.inputStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target.takeIf { it.isFile && it.length() > 0 }?.absolutePath
        }.onFailure(Log::e).getOrNull()
    }

    private fun hash(value: String): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        digest.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }.getOrDefault(value.hashCode().toString())
}

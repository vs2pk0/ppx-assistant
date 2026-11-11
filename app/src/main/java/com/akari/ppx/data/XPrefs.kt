package com.akari.ppx.data

import android.content.SharedPreferences
import android.os.Bundle
import com.akari.ppx.utils.HookRuntime
import com.akari.ppx.utils.Log

object XPrefs {
    @PublishedApi
    internal fun prefs(): SharedPreferences? =
        if (HookRuntime.isModern) null else LegacyPreferences.reload()

    @PublishedApi
    internal fun remotePrefs(): SharedPreferences? = HookRuntime.remotePreferences()

    /** Settings snapshot the module app publishes into its own `files/` dir. */
    const val SNAPSHOT_FILE = "settings-snapshot.json"

    @Volatile
    private var snapshotLines: List<String>? = null

    @Volatile
    private var snapshotAt = 0L

    private const val SNAPSHOT_TTL_MS = 3_000L

    /** Where each successful read came from, so a bug report shows it at a glance. */
    private val sourceCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val sourceMisses = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun mark(source: String) {
        sourceCounts.merge(source, 1, Int::plus)
    }

    private fun miss(source: String) {
        sourceMisses.merge(source, 1, Int::plus)
    }

    /** e.g. `remote=42 snapshot=3 provider=0 default=0`. */
    fun describeSources(): String {
        fun part(map: Map<String, Int>) = listOf("remote", "snapshot", "legacy", "provider", "default")
            .joinToString(" ") { "$it=${map[it] ?: 0}" }
        val hit = part(sourceCounts)
        val miss = part(sourceMisses)
        return "hits[$hit] empty[$miss]"
    }

    /** One-line health report written after hooks are installed. */
    fun healthReport(): String {
        val remote = remotePrefs()
        val remoteKeys = runCatching { remote?.all?.size ?: -1 }.getOrDefault(-1)
        val snapshot = runCatching { HookRuntime.listModuleFiles().contains(SNAPSHOT_FILE) }
            .getOrDefault(false)
        return "XPrefs remote=${if (remote == null) "missing" else "ok(${remoteKeys} keys)"}" +
                " snapshotFile=$snapshot legacy=${if (legacyUnreadable) "unreadable" else "unknown"}" +
                " provider=${if (bridgeDisabled) "disabled" else "untried"} ${describeSources()}"
    }

    /** Set once the fallback channel is proven unusable, to avoid repeated Binder calls. */
    @Volatile
    private var bridgeDisabled = false

    @Volatile
    private var legacyUnreadable = false

    @Volatile
    private var missLogged = false

    /**
     * Settings read order:
     *  1. libxposed remote preferences — the same channel [Prefs] publishes to.
     *  2. Provider bridge — framework independent fallback (disabled after a failure).
     *  3. legacy XSharedPreferences mirror — only readable on old frameworks.
     */
    @PublishedApi
    internal fun read(key: String, default: Any): Any {
        val remote = remotePrefs()
        if (remote != null) {
            val remoteValue = runCatching {
                if (remote.contains(key)) value(remote, key, default) else null
            }.onFailure(Log::e).getOrNull()
            if (remoteValue != null) {
                mark("remote")
                return remoteValue
            }
            miss("remote")
        } else {
            miss("remote")
        }
        // The module app's own snapshot is published atomically together with
        // the remote preferences, so prefer it over the legacy mirror, which
        // can lag behind or stay unreadable on modern Android.
        snapshotRead(key, default)?.let { mark("snapshot"); return it }
        miss("snapshot")
        if (!HookRuntime.isModern && !legacyUnreadable) {
            val legacyValue = runCatching {
                val prefs = prefs()
                if (prefs == null) {
                    legacyUnreadable = true
                    null
                } else {
                    value(prefs, key, default)
                }
            }.onFailure(Log::e).getOrNull()
            if (legacyValue != null) {
                mark("legacy")
                return legacyValue
            }
            miss("legacy")
        }
        bridgeRead(key, default)?.let { mark("provider"); return it }
        miss("provider")
        mark("default")
        if (!missLogged) {
            missLogged = true
            Log.w("XPrefs no live source; returning defaults for $key")
        }
        return default
    }

    /**
     * Reads the module app's published settings snapshot.
     *
     * Some LSPosed builds (2.0.x, and 2.1/2.2 when the daemon has not created
     * remote preferences yet) return an empty group instead of the saved
     * values. That silently yields defaults, so every toggle appears dead. The
     * module app mirrors its DataStore to a plain JSON file in its own
     * `files/` dir, which the framework lets the hooked process read without
     * any permission.
     */
    /** Reads ONLY the snapshot channel; used by the startup self-check. */
    fun snapshotOnly(key: String, default: Any): Any? = snapshotRead(key, default)

    private fun snapshotRead(key: String, default: Any): Any? {
        val now = System.currentTimeMillis()
        val cached = snapshotLines
        val lines = if (cached != null && now - snapshotAt < SNAPSHOT_TTL_MS) cached
        else runCatching {
            val raw = HookRuntime.readModuleFile(SNAPSHOT_FILE) ?: return null
            raw.lines().also {
                snapshotLines = it
                snapshotAt = now
            }
        }.getOrNull() ?: return null
        return parseSnapshot(lines, key, default)
    }

    /**
     * Parses the tab-separated snapshot (`key<TAB>value` per line).
     *
     * Kept pure and internal so the line protocol is unit tested instead of
     * only exercised on a device.
     */
    internal fun parseSnapshot(lines: List<String>, key: String, default: Any): Any? {
        if (lines.isEmpty()) return null
        val raw = lines.firstOrNull { it.startsWith("$key\t") || it.startsWith("$key=") }
            ?: return null
        if (raw.length <= key.length) return if (default is String) "" else null
        val value = raw.substring(key.length + 1)
        return when (default) {
            is Boolean -> value.toBooleanStrictOrNull()
            else -> value
        }
    }

    private fun bridgeRead(key: String, default: Any): Any? {
        if (bridgeDisabled) return null
        val ctx = runCatching { com.akari.ppx.xp.Init.ctx }.getOrNull() ?: return null
        val extras = Bundle().apply {
            putString(BridgeProtocol.KEY, key)
            putString(
                BridgeProtocol.TYPE,
                if (default is Boolean) BridgeProtocol.TYPE_BOOLEAN else BridgeProtocol.TYPE_STRING
            )
        }
        return runCatching {
            val reply = com.akari.ppx.utils.bridgeCall(
                ctx, BridgeProtocol.uri(), BridgeProtocol.METHOD_GET, extras
            )
            if (default is Boolean) reply?.getBoolean(BridgeProtocol.VALUE, default) ?: default else
                reply?.getString(BridgeProtocol.VALUE) ?: default
        }.onFailure {
            bridgeDisabled = true
            Log.w("XPrefs bridge disabled: ${it.message}")
        }.getOrNull()
    }

    private fun value(prefs: SharedPreferences, key: String, default: Any): Any? = when (default) {
        is Boolean -> prefs.getBoolean(key, default)
        is String -> prefs.getString(key, default) ?: default
        else -> default
    }

    inline operator fun <reified T> invoke(key: String, defValue: T? = null): T =
        when (T::class.java) {
            String::class.java -> read(key, (defValue ?: "") as String)
            java.lang.Boolean::class.java -> if (!AutomationAvailability.isSettingAvailable(key)) false
                else read(key, (defValue ?: false) as Boolean)
            else -> null
        } as T

    /** True when at least one configuration channel is usable. */
    fun anySourceAvailable(): Boolean {
        if (remotePrefs() != null) return true
        if (!bridgeDisabled) return true
        return !legacyUnreadable && runCatching { prefs() != null }.getOrDefault(false)
    }
}

@file:Suppress("unchecked_cast")

package com.akari.ppx.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import com.akari.ppx.App
import com.akari.ppx.data.Const.PREFS_NAME
import com.akari.ppx.data.Const.SETTINGS_REVISION
import com.akari.ppx.utils.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

object Prefs {
    /**
     * Result of the last save, shown in the UI.
     *
     * A toggle that only lands in DataStore looks enabled in the module app but
     * the hooked process keeps reading defaults, which users report as "设置了
     * 没有效果". Making the publish result visible turns that into a one-glance
     * diagnosis instead of a support round trip.
     */
    val syncStatus = androidx.compose.runtime.mutableStateOf("尚未同步")

    val lastSyncStatus: String
        get() = syncStatus.value

    /**
     * Startup self-check: the app can be restarted at any time, so the last
     * publish result is not a valid status. What matters is whether the
     * framework service is reachable and the hooks can read our snapshot.
     */
    fun probeSyncStatus() {
        syncStatus.value = runCatching {
            val service = FrameworkScopeState.service()
                ?: return@runCatching "连接框架中…（若一直如此请重启模块或检查作用域）"
            val files = runCatching { service.listRemoteFiles()?.toList() }.getOrNull()
            val snapshot = files?.contains(XPrefs.SNAPSHOT_FILE) == true
            val keys = runCatching { service.getRemotePreferences(PREFS_NAME)?.all?.size ?: 0 }
                .getOrDefault(0)
            when {
                snapshot && keys > 0 -> "同步正常（$keys 项设置）"
                snapshot -> "同步正常（快照通道）"
                keys > 0 -> "远程偏好可用，快照待生成"
                else -> "已连接但尚未同步过设置"
            }
        }.getOrElse { "自检失败：${it.message}" }
        Log.i("Prefs sync self-check: ${syncStatus.value}")
    }

    private val Context.dataStore by preferencesDataStore(PREFS_NAME)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore
        get() = App.context.dataStore
    val dsData by lazy {
        runBlocking(scope.coroutineContext) { dataStore.data.stateIn(scope) }
    }

    inline fun <reified T> get(key: String, defaultValue: T? = null): T? =
        runBlocking(scope.coroutineContext) {
            dataStore.data.first()[getPrefsKey<T>(key)] ?: defaultValue
        }

    private val syncMutex = Mutex()

    inline fun <reified T> set(key: String, value: T) {
        // Start in invocation order, including rapid repeated changes to the same key.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { save(getPrefsKey(key), value) }
    }

    inline fun <reified T> setBlocking(key: String, value: T) {
        runBlocking(scope.coroutineContext) { save(getPrefsKey(key), value) }
    }

    @PublishedApi
    internal suspend fun <T> save(key: Preferences.Key<T>, value: T) {
        syncMutex.withLock {
            var changed = false
            val snapshot = dataStore.edit { prefs ->
                if (prefs[key] != value) {
                    prefs[key] = value
                    prefs[stringPreferencesKey(SETTINGS_REVISION)] = UUID.randomUUID().toString()
                    changed = true
                }
            }
            if (changed) Log.i("Prefs set ${key.name}=${displayValue(value)}")
            // Publish the value and revision together. A failed remote service must not
            // prevent the legacy mirror from receiving the saved configuration.
            runCatching { ModuleSharedPrefs.syncAll(snapshot) }.onFailure(Log::e)
            runCatching { mirrorToSharedStorage(snapshot) }.onFailure(Log::e)
            val remoteState = if (FrameworkScopeState.remotePreferences() == null) "missing" else "ok"
            publishRemote(FrameworkScopeState.remotePreferences(), snapshot)
            val fileState = publishSnapshot(snapshot)
            syncStatus.value = when {
                remoteState == "ok" && fileState.startsWith("ok") -> "同步正常"
                fileState.startsWith("ok") -> "远程偏好不可用，已走文件通道"
                remoteState == "ok" -> "远程偏好已写入，文件通道失败"
                else -> "同步失败：框架服务未连接"
            }
            Log.i("Prefs synced ${key.name} remote=$remoteState file=$fileState status=${syncStatus.value}")
        }
    }

    /** Keeps secrets out of the readable log while still showing the value. */
    private fun displayValue(value: Any?): String = when (value) {
        is String -> if (value.length > 80) value.take(80) + "…" else value
        else -> value.toString()
    }

    suspend fun flush() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val snapshot = dataStore.data.first()
            runCatching { mirrorToSharedStorage(snapshot) }.onFailure(Log::e)
            ModuleSharedPrefs.syncAll(snapshot)
            publishRemote(FrameworkScopeState.remotePreferences(), snapshot)
            publishSnapshot(snapshot)
        }
    }

    fun syncMirror() {
        runBlocking(scope.coroutineContext) {
            syncMutex.withLock {
                val snapshot = dataStore.data.first()
                ModuleSharedPrefs.syncAll(snapshot)
                publishSnapshot(snapshot)
            }
        }
    }

    /** Import a validated batch in one DataStore transaction, then publish it once. */
    fun importBackup(values: Map<String, Any>) = runBlocking(scope.coroutineContext) {
        syncMutex.withLock {
            dataStore.edit { prefs ->
                values.forEach { (name, value) ->
                    when (value) {
                        is Boolean -> prefs[booleanPreferencesKey(name)] = value
                        is String -> prefs[stringPreferencesKey(name)] = value
                        is Int -> prefs[androidx.datastore.preferences.core.intPreferencesKey(name)] = value
                        is Long -> prefs[androidx.datastore.preferences.core.longPreferencesKey(name)] = value
                        is Float -> prefs[androidx.datastore.preferences.core.floatPreferencesKey(name)] = value
                        is Set<*> -> prefs[androidx.datastore.preferences.core.stringSetPreferencesKey(name)] = value.map { it as String }.toSet()
                        else -> error("不支持的设置类型：$name")
                    }
                }
                prefs[stringPreferencesKey(SETTINGS_REVISION)] = UUID.randomUUID().toString()
            }
        }
        // Publishing failures do not invalidate the committed local settings.
        runCatching { flush() }.onFailure(Log::e)
    }

    fun syncRemote(sharedPreferences: SharedPreferences? = FrameworkScopeState.remotePreferences()) {
        if (sharedPreferences == null) return
        runBlocking(scope.coroutineContext) {
            syncMutex.withLock {
                val snapshot = dataStore.data.first()
                publishRemote(sharedPreferences, snapshot)
                publishSnapshot(snapshot)
            }
        }
    }

    /**
     * Publishes a tab-separated settings snapshot into the framework's module
     * files dir (`<lspd>/config/<user>/<pkg>/files/`).
     *
     * This is the channel that survives every framework quirk we have seen in
     * the field: it needs no storage permission, no package visibility and no
     * Binder provider, and the hooked process reads it with
     * `XposedInterface.openRemoteFile` (read-only on that side, which is all it
     * needs). Remote preferences remain the primary source, this is the
     * fallback when the daemon returns an empty group.
     */
    private fun publishSnapshot(snapshot: Preferences): String {
        val service = runCatching { FrameworkScopeState.service() }.getOrNull()
            ?: return "no-service"
        return runCatching {
            val text = buildString {
                snapshot.asMap().forEach { (key, value) ->
                    val flat = when (value) {
                        is Set<*> -> value.filterIsInstance<String>().joinToString(",")
                        else -> value.toString()
                    }
                    // Tabs and newlines would break the line protocol.
                    append(key.name).append('\t')
                        .append(flat.replace('\t', ' ').replace('\n', ' '))
                        .append('\n')
                }
            }.toByteArray()
            val pfd = service.openRemoteFile(XPrefs.SNAPSHOT_FILE)
            try {
                val fd = pfd.fileDescriptor
                android.system.Os.lseek(fd, 0, android.system.OsConstants.SEEK_SET)
                var written = 0
                while (written < text.size) {
                    written += android.system.Os.write(fd, text, written, text.size - written)
                }
                android.system.Os.ftruncate(fd, text.size.toLong())
            } finally {
                runCatching { pfd.close() }
            }
            "ok(${text.size}B)"
        }.onFailure {
            if (!snapshotWarned) {
                snapshotWarned = true
                Log.w("Prefs snapshot publish failed: ${it.message} (falling back to remote prefs)")
            }
        }.getOrDefault("failed")
    }

    @Volatile
    private var snapshotWarned = false

    /**
     * Visible copy of the settings in the module's shared directory.
     *
     * This is best effort: the module UI usually cannot write the host's
     * Android/media folder (scoped storage), and the host reads settings
     * through remote preferences anyway. A failure must not spam the log with
     * a stack trace on every switch toggle.
     */
    private fun mirrorToSharedStorage(snapshot: Preferences) {
        runCatching {
            val dir = com.akari.ppx.utils.StoragePaths.root()
            val json = snapshot.asMap().mapValues { (_, value) ->
                when (value) {
                    is Set<*> -> value.filterIsInstance<String>()
                    else -> value
                }
            }
            val target = java.io.File(dir, "settings.json")
            if (!dir.isDirectory && !dir.mkdirs()) {
                warnMirrorOnce("cannot create ${dir.absolutePath}")
                return
            }
            target.writeText(
                com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json)
            )
        }.onFailure {
            warnMirrorOnce(it.message ?: it.javaClass.simpleName)
        }
    }

    private var mirrorWarned = false

    private fun warnMirrorOnce(reason: String) {
        if (mirrorWarned) return
        mirrorWarned = true
        Log.w("Prefs mirror skipped: $reason (settings still go through remote preferences)")
    }

    private fun publishRemote(remote: SharedPreferences?, snapshot: Preferences) {
        if (remote == null) return
        runCatching {
            val editor = remote.edit().clear()
            snapshot.asMap().forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key.name, value)
                    is Boolean -> editor.putBoolean(key.name, value)
                    is Int -> editor.putInt(key.name, value)
                    is Long -> editor.putLong(key.name, value)
                    is Float -> editor.putFloat(key.name, value)
                    is Set<*> -> editor.putStringSet(key.name, value.filterIsInstance<String>().toSet())
                }
            }
            check(editor.commit()) { "Remote settings synchronization failed" }
        }.onFailure(Log::e)
    }

    inline fun <reified T> getPrefsKey(key: String): Preferences.Key<T> =
        when (T::class.java) {
            Boolean::class.java -> booleanPreferencesKey(key)
            else -> stringPreferencesKey(key)
        } as Preferences.Key<T>
}

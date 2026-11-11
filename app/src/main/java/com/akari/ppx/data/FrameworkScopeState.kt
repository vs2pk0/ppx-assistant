package com.akari.ppx.data

import android.content.SharedPreferences
import com.akari.ppx.data.Const.PREFS_NAME
import com.akari.ppx.data.Const.TARGET_APP_ID
import com.akari.ppx.utils.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArraySet

object FrameworkScopeState {
    interface Listener {
        fun onStateChanged(active: Boolean?)
    }

    @Volatile
    private var registered = false

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var lastScope: Set<String>? = null

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun init() {
        if (registered) return
        registered = true
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    this@FrameworkScopeState.service = service
                    lastScope = readScope(service)
                    // Republish on every (re)bind: a daemon that answered with
                    // an empty group (seen on some 2.x builds after an update)
                    // leaves the host reading defaults, i.e. "settings do
                    // nothing", until the next explicit save.
                    Prefs.syncRemote(service.getRemotePreferences(PREFS_NAME))
                    verifyRemote(service)
                    // The framework service is the only writer of the module
                    // files dir, so refresh the snapshot used by the hooks as
                    // soon as it is available.
                    runCatching { Prefs.syncMirror() }.onFailure(Log::e)
                    runCatching { Prefs.probeSyncStatus() }.onFailure(Log::e)
                    Log.i("FrameworkScopeState bound scope=$lastScope")
                    notifyListeners()
                }

                override fun onServiceDied(service: XposedService) {
                    if (this@FrameworkScopeState.service === service) {
                        this@FrameworkScopeState.service = null
                    }
                    lastScope = null
                    Log.i("FrameworkScopeState service died")
                    notifyListeners()
                }
            })
        }.onFailure {
            Log.w("FrameworkScopeState init skipped: ${it.message}")
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onStateChanged(isTargetAppScoped())
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun isTargetAppScoped(): Boolean? {
        val currentService = service
        if (currentService != null) {
            lastScope = readScope(currentService)
        }
        val scope = lastScope ?: return null
        return scope.contains(TARGET_APP_ID)
    }

    /** Live framework service, used to publish files the hooks can read. */
    fun service(): XposedService? = service

    /**
     * Confirms the daemon actually stored our settings, and republishes once
     * when it reports an empty group. This is the field failure where the
     * module UI shows every switch on, yet the hooked app behaves as if all of
     * them were off.
     */
    private fun verifyRemote(service: XposedService) {
        runCatching {
            val remote = service.getRemotePreferences(PREFS_NAME)
            val remoteKeys = remote?.all?.size ?: 0
            Log.i("FrameworkScopeState remote keys=$remoteKeys")
            if (remoteKeys > 0) return
            val wanted = runBlocking { Prefs.dataStore.data.first().asMap().size }
            if (wanted <= 0) return
            Log.w("FrameworkScopeState remote group empty, republishing $wanted settings")
            Prefs.syncRemote(remote)
            val after = runCatching { remote?.all?.size ?: 0 }.getOrDefault(0)
            Log.i("FrameworkScopeState republish result keys=$after")
        }.onFailure(Log::e)
    }

    fun remotePreferences(): SharedPreferences? = runCatching {
        service?.getRemotePreferences(PREFS_NAME)
    }.onFailure {
        Log.e("FrameworkScopeState remote preferences unavailable")
        Log.e(it)
    }.getOrNull()

    private fun notifyListeners() {
        val active = isTargetAppScoped()
        listeners.forEach { listener ->
            runCatching { listener.onStateChanged(active) }.onFailure(Log::e)
        }
    }

    private fun readScope(service: XposedService): Set<String>? = runCatching {
        val scope = service.scope
        Log.i("FrameworkScopeState raw scope=$scope")
        scope.filterNotNull().toSet()
    }.onFailure {
        Log.e("FrameworkScopeState read scope failed")
        Log.e(it)
    }.getOrNull()
}

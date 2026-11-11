package com.akari.ppx

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import com.akari.ppx.data.FrameworkScopeState
import com.akari.ppx.data.Prefs
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.LogStore
import com.akari.ppx.utils.RuntimeLog

class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        context = base
        // Log before anything else so settings-sync failures are captured too.
        RuntimeLog.install("module", LogStore.file(base))
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("Module app start version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        Log.i(deviceInfo())
        val shared = com.akari.ppx.utils.SharedBridge.root(this)
        val readable = com.akari.ppx.utils.SharedBridge.readable(this)
        Log.i("Module shared root=${shared?.absolutePath ?: "n/a"} readable=$readable")
        if (!readable) Log.w("Module cannot read host media dir; grant 所有文件访问 to read host logs")
        FrameworkScopeState.init()
        Prefs.syncMirror()
        Prefs.probeSyncStatus()
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context

        /** Everything support needs to identify the environment from one line. */
        fun deviceInfo(): String = runCatching {
            "Device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" +
                    " android=${android.os.Build.VERSION.RELEASE}(${android.os.Build.VERSION.SDK_INT})" +
                    " abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"
        }.getOrDefault("Device unknown")
    }
}

package com.akari.ppx.xp.hook.purity

import android.app.Activity
import android.content.Context
import android.os.Build
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.xp.Init.mainActivityClass
import com.akari.ppx.xp.hook.BaseHook

/** The system draws this window before the host process can run any hooks. */
class StartingWindowHook : BaseHook {
    override fun onHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val enabled = XPrefs<Boolean>("skip_splash")
        requireNotNull(mainActivityClass).hookAfterMethod("onResume") { param ->
            val activity = param.thisObject as Activity
            val state = activity.getSharedPreferences("piyou_starting_window", Context.MODE_PRIVATE)
            val applied = state.getBoolean("applied", false)
            if (!enabled && !applied) return@hookAfterMethod
            runCatching {
                // Persist a framework theme that is also resolvable outside the host process.
                // Module resources and an Activity.setTheme call cannot affect that process.
                activity.splashScreen.setSplashScreenTheme(
                    if (enabled) android.R.style.Theme_Light_NoTitleBar else 0
                )
                state.edit().putBoolean("applied", enabled).apply()
                Log.i("StartingWindowHook system theme applied skip=$enabled (next launch)")
            }.onFailure(Log::e)
        }
    }
}

package com.akari.ppx.xp.hook.misc

import android.app.Activity
import android.widget.Toast
import com.akari.ppx.BuildConfig.VERSION_NAME
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.utils.Log
import com.akari.ppx.xp.Init.mainActivityClass
import com.akari.ppx.xp.ModuleEntryBridge
import com.akari.ppx.xp.hook.BaseHook

/** Report the current process installation, without network requests or startup dialogs. */
class HintHook : BaseHook {
    override fun onHook() {
        var shown = false
        mainActivityClass?.hookAfterMethod("onWindowFocusChanged", Boolean::class.java) { param ->
            if (shown || param.args[0] != true || ModuleEntryBridge.installedCount == 0) return@hookAfterMethod
            shown = true
            val failed = ModuleEntryBridge.failedCount
            val message = if (failed == 0) "皮友助手 $VERSION_NAME 加载成功"
                else "皮友助手已加载，$failed 项功能初始化失败"
            (param.thisObject as Activity).runOnUiThread {
                Toast.makeText(param.thisObject as Activity, message, Toast.LENGTH_SHORT).show()
            }
            Log.i("Startup status: $message; hooks=${ModuleEntryBridge.installedCount}")
        }
    }
}

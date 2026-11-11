package com.akari.ppx.xp.hook.misc

import android.app.Activity
import android.widget.Toast
import com.akari.ppx.BuildConfig.VERSION_NAME
import com.akari.ppx.data.Const.SETTINGS_REVISION
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.HookRuntime
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.utils.Log
import com.akari.ppx.xp.Init.mainActivityClass
import com.akari.ppx.xp.ModuleEntryBridge
import com.akari.ppx.xp.hook.BaseHook

/** Report the current process installation, without network requests or startup dialogs. */
class HintHook : BaseHook {
    override fun onHook() {
        var shown = false
        var warnedRevision: String? = null
        mainActivityClass?.hookAfterMethod("onWindowFocusChanged", Boolean::class.java) { param ->
            if (param.args[0] != true || ModuleEntryBridge.installedCount == 0) return@hookAfterMethod
            val activity = param.thisObject as Activity
            val currentRevision = XPrefs<String>(SETTINGS_REVISION)
            val loadedRevision = ModuleEntryBridge.loadedSettingsRevision
            if (currentRevision.isNotEmpty()
                && loadedRevision != null
                && currentRevision != loadedRevision
                && currentRevision != warnedRevision
            ) {
                warnedRevision = currentRevision
                toast(activity, "设置已更新，请在系统应用信息中强行停止皮皮虾后重新打开")
                Log.i("Startup status: settings changed after process start; warm start warned")
                return@hookAfterMethod
            }
            if (shown) return@hookAfterMethod
            shown = true
            val failed = ModuleEntryBridge.failedCount
            val message = buildString {
                append(
                    if (failed == 0) "皮友助手 $VERSION_NAME 加载成功"
                    else "皮友助手已加载，$failed 项功能初始化失败"
                )
                if (!settingsReadable()) append("，设置读取失败")
            }
            toast(activity, message)
            Log.i("Startup status: $message; hooks=${ModuleEntryBridge.installedCount}")
        }
    }

    private fun settingsReadable(): Boolean = XPrefs.anySourceAvailable()

    private fun toast(activity: Activity, message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }
}

package com.akari.ppx.data

import android.content.SharedPreferences
import com.akari.ppx.BuildConfig.APPLICATION_ID
import com.akari.ppx.data.Const.PREFS_NAME
import com.akari.ppx.utils.HookRuntime
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.checkUnless
import de.robv.android.xposed.XSharedPreferences

object XPrefs {
    @PublishedApi
    internal val sharedPrefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        XSharedPreferences(APPLICATION_ID, PREFS_NAME)
    }

    @PublishedApi
    internal fun prefs(): XSharedPreferences = sharedPrefs.also {
        runCatching { it.reload() }.onFailure { error ->
            Log.e("XPrefs reload failed")
            Log.e(error)
        }
        runCatching {
            Log.i("XPrefs file=${it.file} canRead=${it.file?.canRead()}")
        }.onFailure(Log::e)
    }

    @PublishedApi
    internal fun remotePrefs(): SharedPreferences? = HookRuntime.remotePreferences()?.also {
        Log.i("XPrefs remote preferences available")
    }

    inline operator fun <reified T> invoke(key: String, defValue: T? = null): T =
        when (T::class.java) {
            String::class.java -> remotePrefs()?.getString(key, (defValue ?: "") as String)
                ?: prefs().getString(key, (defValue ?: "") as String) ?: ""
            java.lang.Boolean::class.java -> if (!AutomationAvailability.isSettingAvailable(key)) false
                else remotePrefs()?.getBoolean(key, (defValue ?: false) as Boolean)
                    ?: prefs().getBoolean(key, (defValue ?: false) as Boolean)
            else -> null
        } as T

    inline fun checkUnless(key: String, unsatisfiedAction: () -> Unit) =
        XPrefs<Boolean>(key).checkUnless(true) { unsatisfiedAction() }
}

package com.akari.ppx.test

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.akari.ppx.data.FrameworkScopeState
import com.akari.ppx.data.Prefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Signed test APK only: snapshots/restores preferences without touching account data. */
class PreferenceInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle) {
        this.arguments = arguments
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            if (arguments.getString("iconTest") == "true") {
                val alias = android.content.ComponentName(targetContext, "com.akari.ppx.ui.MainActivityAlias")
                val manager = targetContext.packageManager
                val original = manager.getComponentEnabledSetting(alias)
                try {
                    com.akari.ppx.utils.hideIcon(targetContext, true)
                    check(manager.getComponentEnabledSetting(alias) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                    com.akari.ppx.utils.hideIcon(targetContext, false)
                    check(manager.getComponentEnabledSetting(alias) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                    result.putString("iconTest", "PASS hide and show launcher alias")
                } finally {
                    manager.setComponentEnabledSetting(alias, original, android.content.pm.PackageManager.DONT_KILL_APP)
                }
                finish(Activity.RESULT_OK, result)
                return
            }
            val snapshot = runBlocking { Prefs.dataStore.data.first() }
            result.putString("before", JSONObject(snapshot.asMap().mapKeys { it.key.name }).toString())
            arguments.getString("values")?.let { json ->
                val values = JSONObject(json)
                runBlocking {
                    Prefs.dataStore.edit { prefs ->
                        if (arguments.getString("replace") == "true") prefs.clear()
                        values.keys().forEach { key ->
                            when (val value = values.get(key)) {
                                is Boolean -> prefs[booleanPreferencesKey(key)] = value
                                else -> prefs[stringPreferencesKey(key)] = value.toString()
                            }
                        }
                    }
                }
                repeat(100) {
                    if (FrameworkScopeState.remotePreferences() == null) Thread.sleep(100)
                }
                check(FrameworkScopeState.remotePreferences() != null) { "LSPosed service unavailable" }
                Prefs.syncRemote()
                Prefs.syncMirror()
            }
            result.putString("after", runBlocking {
                JSONObject(Prefs.dataStore.data.first().asMap().mapKeys { it.key.name }).toString()
            })
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

}

package com.akari.ppx.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.Preferences
import com.akari.ppx.App
import com.akari.ppx.data.Const.PREFS_NAME
import com.akari.ppx.utils.Log

object ModuleSharedPrefs {
    private val sharedPreferences: SharedPreferences by lazy {
        val context = App.context
        try {
            @Suppress("DEPRECATION")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
        } catch (error: SecurityException) {
            // Modern LSPosed uses remote preferences; world-readable files
            // are only available when the legacy framework grants access.
            Log.i("ModuleSharedPrefs using private mirror; modern settings use remote preferences")
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun prefs(): SharedPreferences = sharedPreferences

    fun mirror(key: String, value: Any?) {
        prefs().edit().apply {
            when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> putString(key, value.toString())
            }
        }.apply()
    }

    fun syncAll(preferences: Preferences) {
        val editor = prefs().edit().clear()
        preferences.asMap().forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key.name, value)
                is Boolean -> editor.putBoolean(key.name, value)
                is Int -> editor.putInt(key.name, value)
                is Long -> editor.putLong(key.name, value)
                is Float -> editor.putFloat(key.name, value)
                is Set<*> -> editor.putStringSet(key.name, value.filterIsInstance<String>().toSet())
                else -> editor.putString(key.name, value.toString())
            }
        }
        editor.apply()
    }
}

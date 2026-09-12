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

object Prefs {
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

    inline fun <reified T> set(key: String, value: T) {
        scope.launch {
            dataStore.edit { prefs -> prefs[getPrefsKey(key)] = value }
            syncRemoteValue(key, value)
            ModuleSharedPrefs.mirror(key, value)
        }
    }

    inline fun <reified T> setBlocking(key: String, value: T) {
        runBlocking(scope.coroutineContext) {
            dataStore.edit { prefs -> prefs[getPrefsKey(key)] = value }
            syncRemoteValue(key, value)
            ModuleSharedPrefs.mirror(key, value)
        }
    }

    fun syncMirror() {
        runBlocking(scope.coroutineContext) {
            ModuleSharedPrefs.syncAll(dataStore.data.first())
        }
    }

    fun syncRemote(sharedPreferences: SharedPreferences? = FrameworkScopeState.remotePreferences()) {
        val remote = sharedPreferences ?: return
        runBlocking(scope.coroutineContext) {
            val editor = remote.edit().clear()
            dataStore.data.first().asMap().forEach { (key, value) ->
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

    @PublishedApi
    internal fun syncRemoteValue(key: String, value: Any?) {
        val editor = FrameworkScopeState.remotePreferences()?.edit() ?: return
        when (value) {
            null -> editor.remove(key)
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> editor.putString(key, value.toString())
        }
        editor.apply()
    }

    inline fun <reified T> getPrefsKey(key: String): Preferences.Key<T> =
        when (T::class.java) {
            Boolean::class.java -> booleanPreferencesKey(key)
            else -> stringPreferencesKey(key)
        } as Preferences.Key<T>
}

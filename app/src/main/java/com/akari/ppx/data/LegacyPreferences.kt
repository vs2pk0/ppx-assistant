package com.akari.ppx.data

import android.content.SharedPreferences
import com.akari.ppx.BuildConfig.APPLICATION_ID
import com.akari.ppx.data.Const.PREFS_NAME
import de.robv.android.xposed.XSharedPreferences

/** Loaded only by the legacy entry; API 102 forbids legacy Xposed calls. */
internal object LegacyPreferences {
    private val prefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { XSharedPreferences(APPLICATION_ID, PREFS_NAME) }.getOrNull()
    }

    fun reload(): SharedPreferences? {
        val current = prefs ?: return null
        if (current.file?.canRead() != true) return null
        current.reload()
        return current
    }
}

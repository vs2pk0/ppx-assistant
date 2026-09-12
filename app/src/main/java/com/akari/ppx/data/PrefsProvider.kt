package com.akari.ppx.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class PrefsProvider : ContentProvider() {
    override fun call(method: String, key: String?, extras: Bundle?): Bundle = Bundle().apply {
        if (method == PrefsType.SET_STRING.method || method == PrefsType.SET_BOOLEAN.method) {
            val uid = android.os.Binder.getCallingUid()
            val allowed = uid == android.os.Process.myUid() ||
                context?.packageManager?.getPackagesForUid(uid)?.contains(Const.TARGET_APP_ID) == true
            check(allowed) { "Only the module and target app may update settings" }
        }
        Prefs.run {
            when (method) {
                PrefsType.STRING.method -> get<String>(key!!)?.let { putString(PrefsType.STRING.key, it) }
                PrefsType.BOOLEAN.method -> get<Boolean>(key!!)?.let { putBoolean(PrefsType.BOOLEAN.key, it) }
                PrefsType.SET_STRING.method -> extras?.getString(PrefsType.SET_STRING.key)?.let {
                    setBlocking(key!!, it)
                    putString(PrefsType.SET_STRING.key, it)
                }
                PrefsType.SET_BOOLEAN.method -> extras?.let {
                    val value = it.getBoolean(PrefsType.SET_BOOLEAN.key, false)
                    setBlocking(key!!, value)
                    putBoolean(PrefsType.SET_BOOLEAN.key, value)
                }
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(p0: Uri, p1: Array<out String>?, p2: String?, p3: Array<out String>?, p4: String?): Cursor? = null

    override fun getType(p0: Uri): String? = null

    override fun insert(p0: Uri, p1: ContentValues?): Uri? = null

    override fun delete(p0: Uri, p1: String?, p2: Array<out String>?): Int = 0

    override fun update(p0: Uri, p1: ContentValues?, p2: String?, p3: Array<out String>?): Int = 0
}

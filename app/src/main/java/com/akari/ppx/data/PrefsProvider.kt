package com.akari.ppx.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.akari.ppx.utils.LogBridge
import com.akari.ppx.utils.LogStore

class PrefsProvider : ContentProvider() {
    override fun call(method: String, key: String?, extras: Bundle?): Bundle {
        val reply = Bundle()
        val uid = Binder.getCallingUid()
        val trusted = uid == Process.myUid() ||
            context?.packageManager?.getPackagesForUid(uid)?.contains(Const.TARGET_APP_ID) == true
        if (!trusted) return reply
        if (handleBridgeCall(method, extras, reply)) return reply
        if (key == null) return reply
        when (method) {
            PrefsType.STRING.method -> Prefs.get<String>(key)?.let { reply.putString(PrefsType.STRING.key, it) }
            PrefsType.BOOLEAN.method -> Prefs.get<Boolean>(key)?.let { reply.putBoolean(PrefsType.BOOLEAN.key, it) }
            PrefsType.SET_STRING.method -> extras?.getString(PrefsType.SET_STRING.key)?.let {
                Prefs.setBlocking(key, it)
                reply.putString(PrefsType.SET_STRING.key, it)
            }
            PrefsType.SET_BOOLEAN.method -> extras?.let {
                val value = it.getBoolean(PrefsType.SET_BOOLEAN.key, false)
                Prefs.setBlocking(key, value)
                reply.putBoolean(PrefsType.SET_BOOLEAN.key, value)
            }
        }
        return reply
    }

    /**
     * Host process log forwarding. The hooked 皮皮虾 process cannot write the
     * module's private storage, so it hands batches over through this provider.
     * Only our own app and the scoped host may append.
     */
    private fun handleBridgeCall(method: String, extras: Bundle?, reply: Bundle): Boolean {
        if (method != LogBridge.METHOD_APPEND && method != LogBridge.METHOD_READ &&
            method != LogBridge.METHOD_CLEAR && method != BridgeProtocol.METHOD_GET
        ) return false
        val ctx = context ?: return true
        when (method) {
            LogBridge.METHOD_APPEND -> extras?.getStringArray("lines")?.let {
                runCatching { LogStore.append(ctx, it.toList()) }
            }
            LogBridge.METHOD_READ -> {
                val lines = LogStore.read(ctx, extras?.getInt("limit", 800) ?: 800)
                reply.putStringArray("lines", lines.toTypedArray())
            }
            LogBridge.METHOD_CLEAR -> {
                LogStore.clear(ctx)
                reply.putBoolean("cleared", true)
            }
            BridgeProtocol.METHOD_GET -> {
                // Independent settings channel for the hooked process: works
                // even when the framework's remote preferences are unavailable.
                val name = extras?.getString(BridgeProtocol.KEY) ?: return true
                when (extras?.getString(BridgeProtocol.TYPE)) {
                    BridgeProtocol.TYPE_BOOLEAN -> Prefs.get<Boolean>(name)?.let {
                        reply.putBoolean(BridgeProtocol.VALUE, it)
                    }
                    BridgeProtocol.TYPE_STRING -> Prefs.get<String>(name)?.let {
                        reply.putString(BridgeProtocol.VALUE, it)
                    }
                }
            }
        }
        return true
    }

    override fun onCreate(): Boolean = true

    override fun query(p0: Uri, p1: Array<out String>?, p2: String?, p3: Array<out String>?, p4: String?): Cursor? = null

    override fun getType(p0: Uri): String? = null

    override fun insert(p0: Uri, p1: ContentValues?): Uri? = null

    override fun delete(p0: Uri, p1: String?, p2: Array<out String>?): Int = 0

    override fun update(p0: Uri, p1: ContentValues?, p2: String?, p3: Array<out String>?): Int = 0
}

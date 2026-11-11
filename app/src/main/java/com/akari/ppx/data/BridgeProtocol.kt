package com.akari.ppx.data

import android.net.Uri

/**
 * Contract between the hooked 皮皮虾 process and the module app for the
 * fallback settings channel.
 *
 * `XSharedPreferences` cannot read the module's private file on modern Android,
 * and libxposed remote preferences depend on the framework service binding. When
 * either one fails the host would silently fall back to defaults, which looks
 * exactly like "module is active but every switch does nothing". This provider
 * call is plain Binder and works regardless of the framework version.
 */
object BridgeProtocol {
    const val METHOD_GET = "settings_get"
    const val KEY = "key"
    const val TYPE = "type"
    const val VALUE = "value"
    const val TYPE_BOOLEAN = "boolean"
    const val TYPE_STRING = "string"

    fun uri(): Uri = Uri.parse("content://${com.akari.ppx.BuildConfig.APPLICATION_ID}.CP/")
}

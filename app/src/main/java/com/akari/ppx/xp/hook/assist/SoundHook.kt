@file:Suppress("unused")

package com.akari.ppx.xp.hook.assist

import android.media.SoundPool
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.ctx
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.mainActivityClass
import com.akari.ppx.xp.hook.SwitchHook

class SoundHook : SwitchHook("enable_digg_sound") {
    override fun onHook() {
        val soundKey = "com.sup.android.constants.SettingKeyValues".findClass(cl)
            .getStaticObjectFieldAs<String>("KEY_BDS_DIGG_SOUND_SWITCH")
        setSettingKeyValue { key -> if (key == soundKey) true else null }
        val managerClass = "com.sup.android.manager.b".findClass(cl)
        managerClass.replaceMethod("a", String::class.java, String::class.java) { param ->
            val resource = param.args[0] as String
            val pool = managerClass.getStaticObjectFieldOrNullAs<SoundPool>("c")
            val cache = managerClass.getStaticObjectFieldOrNullAs<MutableMap<String, Int>>("d")
            val bundled = if (pool != null && cache != null &&
                resource in setOf("short_click_sound.mp3", "long_click_sound.mp3")) {
                runCatching { ctx.assets.openFd("sound/$resource").use { pool.load(it, 1) } }.getOrNull()
            } else null
            if (bundled != null && bundled > 0) {
                cache!![param.args[1] as String] = bundled
                null
            } else param.invokeOriginalMethod()
        }
        // Load asynchronously before the first tap. Keep the host's stream tracking
        // and release behavior instead of replacing its playback implementation.
        var initialized = false
        mainActivityClass?.hookAfterMethod("onResume") {
            if (!initialized) {
                runCatching {
                    managerClass.getStaticObjectField("b").callMethod("a")
                    initialized = true
                }.onFailure(Log::e)
            }
        }
    }
}

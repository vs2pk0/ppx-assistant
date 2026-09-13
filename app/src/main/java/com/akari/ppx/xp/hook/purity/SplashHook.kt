package com.akari.ppx.xp.hook.purity

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.xp.Init.mainActivityClass
import com.akari.ppx.xp.hook.SwitchHook

class SplashHook : SwitchHook("skip_splash") {
    override fun onHook() {
        val main = requireNotNull(mainActivityClass)
        val helper = main.getDeclaredField("mSplashHelper").type
        // Keep the host's initialization and dismissal callbacks; select its no-ad path.
        val show = helper.declaredMethods.single { method ->
            val types = method.parameterTypes
            method.returnType == Void.TYPE && types.size == 3 &&
                Activity::class.java.isAssignableFrom(types[0]) &&
                types[1] == Int::class.javaPrimitiveType && types[2] == Boolean::class.javaPrimitiveType
        }
        show.hookBeforeMethod { param -> param.args[2] = false }
        main.hookBeforeMethod("updateFakedSplashViewVisible", Boolean::class.java) { param ->
            param.args[0] = false
        }
        main.hookBeforeMethod("onCreate", Bundle::class.java) { param ->
            (param.thisObject as Activity).window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        }
    }
}

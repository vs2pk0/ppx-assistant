@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.view.View
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.hookAfterConstructor
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook

class BottomViewHook : SwitchHook("remove_bottom_view") {
    override fun onHook() {
        // Channel changes can call the bottom-bar controller without notifying MainActivity.
        // All of the controller's show/hide paths use this animator.
        "com.sup.android.base.main.bottom.i".findClass(cl).apply {
            hookAfterConstructor(View::class.java) { param ->
                (param.args[0] as View).visibility = View.GONE
            }
            hookBeforeMethod("a", Boolean::class.java, Boolean::class.java) { param ->
                param.args[0] = false
                param.args[1] = true
            }
        }
    }
}

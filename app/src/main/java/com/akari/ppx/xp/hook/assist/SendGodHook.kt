@file:Suppress("unused")

package com.akari.ppx.xp.hook.assist

import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook

class SendGodHook : SwitchHook("unlock_send_god_limit") {
    override fun onHook() {
        "com.sup.android.mi.feed.repo.bean.comment.Comment".hookAfterMethod(
            cl,
            "getSendGodStatus"
        ) { param ->
            // Reading a display status must not submit likes or mutate comment identity.
            if (param.result == 3) param.result = 1
        }
    }
}

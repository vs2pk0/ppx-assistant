@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import com.akari.ppx.utils.getIntFieldOrNull
import com.akari.ppx.utils.getObjectFieldOrNullAs
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook

class AvatarHook : SwitchHook("remove_avatar_decoration") {
    override fun onHook() {
        "com.sup.android.mi.usercenter.model.UserInfo".hookAfterMethod(
            cl,
            "getDecorationList"
        ) { param ->
            val list = param.result as? List<*> ?: return@hookAfterMethod
            param.result = list.filterNot { decoration ->
                decoration.getObjectFieldOrNullAs<List<*>>("decorationInfos")?.any {
                    it.getIntFieldOrNull("decorationType") == 2
                } == true
            }
        }
    }
}

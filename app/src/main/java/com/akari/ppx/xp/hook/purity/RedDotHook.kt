@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import android.os.Message
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.utils.Log
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.mainActivityClass
import com.akari.ppx.xp.hook.SwitchHook

class RedDotHook : SwitchHook("remove_red_dots") {
    override fun onHook() {
        "com.sup.superb.feedui.view.tabv2.FeedTabFragmentV2".findClass(cl).apply {
            hookBeforeMethod(declaredMethods.find { m ->
                m.parameterTypes.size == 3 && m.parameterTypes[0] == Boolean::class.java
                        && m.parameterTypes[1] == Int::class.java && m.parameterTypes[2] == Int::class.java
            }?.name, Boolean::class.java, Int::class.java, Int::class.java) { param ->
                param.args[0] = false
                param.args[1] = 0
            }
        }
        // Explicit message count styling takes precedence over hiding the message badge.
        if (!XPrefs<Boolean>("modify_message_counts")) hookMainActivityRedDots()
        "com.sup.android.m_mine.view.subview.MyProfileHeaderLayout".hookBeforeMethod(
            cl,
            "a",
            Boolean::class.java
        ) { param ->
            param.args[0] = false
        }
    }

    private fun hookMainActivityRedDots() {
        val activityClass = mainActivityClass ?: return
        val badgeMethod = activityClass.declaredMethods.firstOrNull { method ->
            method.name == "handleBadgeAndPopup" && method.parameterTypes.size == 1
        }
        val badgeArgType = badgeMethod?.parameterTypes?.firstOrNull()
        when {
            badgeArgType == java.lang.Long.TYPE || badgeArgType == java.lang.Long::class.java -> {
                badgeMethod?.hookBeforeMethod { param ->
                    param.args[0] = 0L
                }
                Log.i("RedDotHook hook MainActivity.handleBadgeAndPopup(long)")
            }

            badgeArgType?.name == "com.sup.android.i_message.i" -> {
                badgeMethod?.hookBeforeMethod { param ->
                    param.args[0] = null
                }
                Log.i("RedDotHook hook MainActivity.handleBadgeAndPopup(i_message)")
            }

            else -> {
                val handleMsgMethod = activityClass.declaredMethods.firstOrNull { method ->
                    method.name == "handleMsg" && method.parameterTypes.size == 1
                            && method.parameterTypes[0] == Message::class.java
                }
                if (handleMsgMethod != null) {
                    handleMsgMethod.hookBeforeMethod { param ->
                        val message = param.args[0] as? Message ?: return@hookBeforeMethod
                        if (message.what == 1) {
                            message.obj = null
                        }
                    }
                    Log.i("RedDotHook fallback MainActivity.handleMsg(Message)")
                } else {
                    Log.i("RedDotHook no MainActivity badge hook matched")
                }
            }
        }

    }
}

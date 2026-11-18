@file:Suppress("unused")

package com.akari.ppx.xp.hook.misc

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.hookAfterAllConstructors
import com.akari.ppx.utils.hookAfterAllMethods
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook

/**
 * The bottom bar is built by MainBottomBarController and later re-shaped by a
 * server-driven tab config that swaps indicator views by position, so specs are
 * hidden at their common source instead: the indicator factory b.a(...) is the
 * only place that creates a tab view, and it receives the spec tag (args[1])
 * plus the resolved label (args[2]).
 */
internal fun hideBottomTabs(vararg tags: String) {
    "com.sup.android.base.main.bottom.b".findClass(cl).hookAfterMethod(
        "a",
        android.content.Context::class.java,
        String::class.java,
        String::class.java,
        Int::class.javaPrimitiveType
    ) { param ->
        val tag = param.args[1]?.toString()
        val label = param.args[2]?.toString()
        if (tag in tags || label in tags) {
            (param.result as? View)?.visibility = View.GONE
        }
    }
}

class TopicTabHook : SwitchHook("hide_bottom_topic_tab") {
    // Slot two is "discovery" (话题) or "task" (福利中心) depending on the host's
    // coin-system flag, so both tags hide the same position.
    override fun onHook() = hideBottomTabs("discovery", "task", "话题", "福利", "福利中心")
}

class MessageTabHook : SwitchHook("hide_bottom_message_tab") {
    override fun onHook() = hideBottomTabs("message", "消息")
}

/** The home top-bar game icon is created solely by MiniGameHelper (dex
 * `feedui.util.aa`; constructor receives the stub and icon view ids). */
class HomeGameEntryHook : SwitchHook("hide_home_game_entry") {
    override fun onHook() {
        "com.sup.superb.feedui.util.aa".findClass(cl).hookAfterAllConstructors { param ->
            val container = param.args[0] as? View ?: return@hookAfterAllConstructors
            val stubId = param.args[1] as? Int ?: return@hookAfterAllConstructors
            val iconId = param.args[2] as? Int ?: return@hookAfterAllConstructors
            container.findViewById<View>(stubId)?.visibility = View.GONE
            container.findViewById<View>(iconId)?.visibility = View.GONE
        }
    }
}

class CoinFloatBallHook : SwitchHook("hide_coin_float_ball") {
    override fun onHook() {
        // Hide the floating views without interrupting the host's task setup.
        for ((service, tag) in listOf(
            "MoneyExcitingService" to "view_tag_money_icon",
            "IntegralService" to "video_channel_float_window"
        )) {
            val name = "com.sup.android.m_integral.$service"
            val type = runCatching { name.findClass(cl) }.getOrNull() ?: continue
            type.hookAfterAllMethods("addCoinVideoFloatView") { param ->
                val container = param.args.firstOrNull() as? ViewGroup ?: return@hookAfterAllMethods
                container.findViewWithTag<View>(tag)?.visibility = View.GONE
            }
        }

        // FeedDurationEntranceMgr draws the reading reward in its own wrapper.
        val durationManager = runCatching {
            "com.sup.superb.feedui.util.j".findClass(cl)
        }.getOrNull() ?: return
        val wrapperField = durationManager.declaredFields.singleOrNull {
            it.type == ViewGroup::class.java
        }?.apply { isAccessible = true } ?: return
        durationManager.hookAfterAllConstructors { param ->
            if (param.args.firstOrNull()?.javaClass?.name != "com.sup.superb.feedui.view.tabv2.FeedTabFragmentV2") {
                return@hookAfterAllConstructors
            }
            val wrapper = wrapperField.get(param.thisObject) as? ViewGroup ?: return@hookAfterAllConstructors
            // Keep the wrapper measured. The host repositions and shows its child on channel changes.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                wrapper.visibility = View.INVISIBLE
            } else {
                wrapper.post { wrapper.visibility = View.INVISIBLE }
            }
        }
    }
}

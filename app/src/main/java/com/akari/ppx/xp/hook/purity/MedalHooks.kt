package com.akari.ppx.xp.hook.purity

import android.view.View
import com.akari.ppx.utils.hookAfterAllConstructors
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook

class NewMedalPopupHook : SwitchHook("hide_new_medal_popup") {
    override fun onHook() {
        // The host's dedicated award dialog. The manual badge page has a separate entry.
        val dialog = cl.loadClass("com.sup.android.module.usercenter.view.a")
        dialog.getDeclaredMethod("show").hookBeforeMethod { it.result = null }
    }
}

class NicknameMedalsHook : SwitchHook("hide_nickname_medals") {
    override fun onHook() {
        val medalView = cl.loadClass("com.sup.android.uikit.widget.MedalEntranceView")
        // The APK renames resource table entries. The host's R fields retain their names.
        val ids = cl.loadClass("com.sup.android.superb.R\$id")
        val nicknameMedals = setOf(
            "feedui_common_cell_part_header_medal_enter",
            "detail_item_user_medal_enter",
            "comment_user_medal_mev",
            "detail_comment_user_medal_mev",
            "feedui_mev_cell_part_god_comment_medal",
            "profile_mev_medal_container"
        ).mapNotNull { name ->
            try {
                ids.getField(name).getInt(null)
            } catch (_: NoSuchFieldException) {
                null
            }
        }.toSet()
        check(nicknameMedals.isNotEmpty()) { "Nickname medal view IDs are unavailable" }

        medalView.hookAfterAllConstructors { param ->
            val view = param.thisObject as View
            if (view.id in nicknameMedals) view.visibility = View.GONE
        }
        medalView.getDeclaredMethod("onVisibilityChanged", View::class.java, Int::class.javaPrimitiveType)
            .hookBeforeMethod { param ->
                val view = param.thisObject as View
                if (param.args[0] === view && param.args[1] == View.VISIBLE && view.id in nicknameMedals) {
                    // Bindings on recycled rows can set the view visible again.
                    view.visibility = View.GONE
                    param.result = null
                }
            }

        // UserInfoHeader renders creator badges separately, in text and image forms.
        val headerConfig = cl.loadClass("com.sup.superb.m_feedui_common.docker.a.a\$a")
        val setShowUserTag = headerConfig.getDeclaredMethod("a", Boolean::class.javaPrimitiveType)
        val headerHolder = cl.loadClass("com.sup.superb.m_feedui_common.docker.a.a\$b")
        val feedCell = cl.loadClass("com.sup.android.mi.feed.repo.bean.cell.AbsFeedCell")
        val userInfo = cl.loadClass("com.sup.android.mi.usercenter.model.UserInfo")
        val bindUserInfo = headerHolder.declaredMethods.single { method ->
            val types = method.parameterTypes
            method.name == "a" && types.size == 4 && types[0] == feedCell &&
                types[1] == userInfo && types[3] == headerConfig
        }
        bindUserInfo.hookBeforeMethod { setShowUserTag.invoke(it.args[3], false) }
    }
}

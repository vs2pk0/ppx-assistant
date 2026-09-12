@file:Suppress("unused", "unchecked_cast", "type_mismatch_warning")

package com.akari.ppx.xp.hook.assist

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.absFeedCellClass
import com.akari.ppx.xp.Init.canShowActionPi
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.enterPi1
import com.akari.ppx.xp.Init.enterPi1Class
import com.akari.ppx.xp.Init.enterPi2
import com.akari.ppx.xp.Init.enterPi2Class
import com.akari.ppx.xp.Init.feedCellUtilCompanionClass
import com.akari.ppx.xp.hook.SwitchHook

class CopyHook : SwitchHook("copy_item") {
    override fun onHook() {
        val actionTypeClass =
            "com.sup.android.i_sharecontroller.model.OptionAction\$OptionActionType".findClass(cl)
        val actionType = actionTypeClass.enumConstants.firstOrNull {
            (it as? Enum<*>)?.name == "ACTION_PI"
        } ?: return
        feedCellUtilCompanionClass!!.replaceMethod(
            canShowActionPi(),
            absFeedCellClass
        ) { true }
        View::class.java.name.hookBeforeMethod(cl, "setTag", Object::class.java) { param ->
            runCatching {
                if (param.args[0] == actionType) {
                    param.thisObject.callMethod("getChildAt", 1)?.callMethod("setText", "复制文字")
                }
            }
        }

        fun HookParam.copyText() {
            val cell = args.getOrNull(1)
            val text = contentText(cell)
            if (text.isNullOrEmpty()) {
                showStickyToast("当前内容没有可复制的文字")
                return
            }
            ((args[0] as Activity).getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                ClipData.newPlainText(text, text)
            )
            showStickyToast("复制成功")
        }

        enterPi1Class!!.replaceMethod(
            enterPi1(),
            Activity::class.java,
            absFeedCellClass,
            String::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.java
        ) { param ->
            param.copyText()
        }
        enterPi2Class!!.replaceMethod(
            enterPi2(),
            Activity::class.java,
            absFeedCellClass,
            String::class.java,
            String::class.java,
            String::class.java,
            HashMap::class.java,
            Boolean::class.java
        ) { param ->
            param.copyText()
        }
    }
    internal fun contentText(cell: Any?): String? =
        cell.callMethodOrNull("getReply")?.callMethodOrNullAs<String>("getText")
            ?: cell.callMethodOrNull("getComment")?.callMethodOrNullAs<String>("getText")
            ?: cell.callMethodOrNull("getFeedItem")?.callMethodOrNullAs<String>("getContent")

}

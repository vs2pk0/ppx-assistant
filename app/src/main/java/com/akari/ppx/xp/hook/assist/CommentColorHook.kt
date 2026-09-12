package com.akari.ppx.xp.hook.assist

import android.app.Dialog
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook

class CommentColorHook : BaseHook {
    override fun onHook() {
        // The server preserves type=7, but comment renderers disable explicit colors by default.
        // Only opt in for the precise markup emitted by this setting.
        val dataClass = "com.sup.android.utils.ce".findClass(cl)
        "com.sup.android.utils.cd".findClass(cl).hookBeforeMethod("a", dataClass) { param ->
            val data = param.args[0] ?: return@hookBeforeMethod
            val text = data.getObjectFieldOrNullAs<String>("h") ?: return@hookBeforeMethod
            if (text.contains("[b type=7 color=#D93025]")) data.setBooleanField("g", true)
        }

        val callback = "com.sup.android.module.publish.view.NewInputCommentDialog\$tryPublish\$2".findClass(cl)
        val dialog = callback.declaredFields.first { Dialog::class.java.isAssignableFrom(it.type) }.type
        // 6.2.0: the final publishing text sanitizer, after emoji and mention processing.
        dialog.hookAfterMethod("e", String::class.java) { param ->
            val text = param.result as? String ?: return@hookAfterMethod
            param.result = CommentColors.format(text, XPrefs("comment_text_color", "none"))
        }
    }
}

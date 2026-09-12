@file:Suppress("unused")

package com.akari.ppx.xp.hook.assist

import com.akari.ppx.utils.replaceMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook
import com.akari.ppx.data.XPrefs

class WordHook : BaseHook {
    override fun onHook() {
        "com.sup.android.module.publish.view.NewInputCommentDialog\$tryPublish$2".replaceMethod(
            cl,
            "invoke",
            String::class.java
        ) { param ->
            if (XPrefs<Boolean>("unlock_illegal_words")) param.args[0] else param.invokeOriginalMethod()
        }
        "com.sup.android.m_illegalword.utils.RuleTable".replaceMethod(cl, "getReplaceMap") { param ->
            if (XPrefs<Boolean>("unlock_illegal_words")) null else param.invokeOriginalMethod()
        }
    }
}
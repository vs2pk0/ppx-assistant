@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.commentResponseClass
import com.akari.ppx.xp.hook.SwitchHook

class CommentHook : SwitchHook("remove_comments") {
    override fun onHook() {
        val keywords = FilterPatterns(XPrefs("remove_comments_keywords")) { Log.i("Invalid comment keyword pattern ignored") }
        val users = FilterPatterns(XPrefs("remove_comments_users")) { Log.i("Invalid comment user pattern ignored") }
        commentResponseClass!!.hookBeforeMethod("a", ArrayList::class.java) { param ->
            val comments = param.args[0] as? List<*> ?: return@hookBeforeMethod
            param.args[0] = ArrayList(comments.filterNot { cell ->
                val comment = cell.callMethodOrNull("getReply")
                    ?: cell.callMethodOrNull("getComment")
                keywords.matches(comment?.getObjectFieldOrNullAs("text")) ||
                    users.matches(comment?.getObjectFieldOrNull("userInfo")?.getObjectFieldOrNullAs("name"))
            })
        }
    }
}

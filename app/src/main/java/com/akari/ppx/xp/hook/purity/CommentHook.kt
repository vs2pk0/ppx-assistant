@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import com.akari.ppx.data.AutoSendGod
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.commentResponseClass
import com.akari.ppx.xp.hook.SwitchHook
import java.util.Collections
import java.util.WeakHashMap

class CommentHook : SwitchHook("remove_comments") {
    override fun onHook() {
        val keywords = FilterPatterns(XPrefs("remove_comments_keywords")) { Log.i("Invalid comment keyword pattern ignored") }
        val users = FilterPatterns(XPrefs("remove_comments_users")) { Log.i("Invalid comment user pattern ignored") }
        commentResponseClass!!.hookBeforeMethod("a", ArrayList::class.java) { param ->
            val comments = param.args[0] as? List<*> ?: return@hookBeforeMethod
            val visible = comments.filterNot { cell ->
                val comment = cell.callMethodOrNull("getReply")
                    ?: cell.callMethodOrNull("getComment")
                keywords.matches(comment?.getObjectFieldOrNullAs("text")) ||
                    users.matches(comment?.getObjectFieldOrNull("userInfo")?.getObjectFieldOrNullAs("name"))
            }
            if (visible.size < comments.size && XPrefs<Boolean>(AutoSendGod.ENABLED)) {
                unfilteredByResponse[param.thisObject] = comments.toList()
            } else {
                unfilteredByResponse.remove(param.thisObject)
            }
            param.args[0] = ArrayList(visible)
        }
    }

    companion object {
        // Auto-send needs to count the comments that this filter removed from the response.
        private val unfilteredByResponse = Collections.synchronizedMap(WeakHashMap<Any, List<*>>())

        internal fun unfilteredComments(response: Any?): List<*>? =
            response?.let { unfilteredByResponse[it] }
    }
}

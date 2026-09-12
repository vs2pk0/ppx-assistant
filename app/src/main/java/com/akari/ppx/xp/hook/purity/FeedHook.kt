@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.feedResponse
import com.akari.ppx.xp.Init.feedResponseClass
import com.akari.ppx.xp.hook.BaseHook

class FeedHook : BaseHook {
    override fun onHook() {
        val flags = listOf<Boolean>(XPrefs("remove_feeds"), XPrefs("remove_official_feeds"),
            XPrefs("remove_promotional_feeds"), XPrefs("remove_live_feeds"))
        if (flags.none { it }) return
        val filter = FeedFilter(flags, XPrefs("remove_feeds_keywords"), XPrefs("remove_feeds_users"))
        feedResponseClass!!.hookBeforeMethod(feedResponse(), String::class.java,
            "com.sup.android.mi.feed.repo.bean.FeedResponse", Boolean::class.java, Int::class.java) { param ->
            val feeds = param.args[1].callMethodOrNullAs<ArrayList<*>>("getData") ?: return@hookBeforeMethod
            val before = feeds.size
            feeds.removeAll(filter::matches)
            if (before != feeds.size) Log.i("FeedHook removed=${before - feeds.size}")
        }
    }
}

/** Match cells independently so that missing fields do not stop a mixed feed. */
internal class FeedFilter(private val flags: List<Boolean>, keywords: String, users: String) {
    private val keywords = FilterPatterns(keywords) { Log.i("Invalid feed keyword pattern ignored") }
    private val users = FilterPatterns(users) { Log.i("Invalid feed user pattern ignored") }

    fun matches(cell: Any?): Boolean {
        val item = cell.callMethodOrNull("getFeedItem")
        val author = item.callMethodOrNull("getAuthor")
        if (flags[0] && (keywords.matches(item.callMethodOrNullAs("getContent")) ||
                    users.matches(author.callMethodOrNullAs("getName")))) return true
        if (flags[1]) {
            val certification = author.callMethodOrNull("getCertifyInfo").callMethodOrNullAs<String>("getDescription")
            if (certification != null && listOf("官方账号", "视频号", "新媒体").any(certification::contains)) return true
        }
        if (flags[2] && item.callMethodOrNull("getPromotionInfo") != null) return true
        return flags[3] && cell != null && generateSequence(cell.javaClass as Class<*>?) { it.superclass }
            .any { it.name == "com.sup.android.mi.feed.repo.bean.cell.LiveSaasFeedCell" }
    }
}

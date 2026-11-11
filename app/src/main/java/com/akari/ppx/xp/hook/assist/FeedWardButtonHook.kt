package com.akari.ppx.xp.hook.assist

import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.akari.ppx.utils.callMethodOrNull
import com.akari.ppx.utils.callMethodOrNullAs
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.xp.hook.SwitchHook
import kotlin.math.roundToInt

@Suppress("unused")
class FeedWardButtonHook : SwitchHook(KEY) {
    override fun onHook() {
        if (!supportsCommentActions("FeedWardButton")) return
        val holder = hostClass("com.sup.superb.feedui.docker.part.an")
        val manager = hostClass("com.sup.superb.feedui.docker.part.al")
        val docker = hostClass("com.sup.superb.dockerbase.misc.DockerContext")
        val feedCell = hostClass("com.sup.android.mi.feed.repo.bean.cell.AbsFeedCell")
        val itemCell = hostClass("com.sup.android.mi.feed.repo.bean.cell.ItemFeedCell")
        val dependency = hostClass("com.sup.android.utils.v")
        val listInfo = hostClass("com.sup.superb.i_feedui.docker.depend.IFragmentInfoProvider")
        val originItem = hostClass("com.sup.superb.feedui.docker.part.a.d")
        val wardListener = hostClass("com.sup.superb.m_feedui_common.view.e")
        val dependencyField = holder.getDeclaredField("c").apply { isAccessible = true }
        val wardField = holder.getDeclaredField("m").apply { isAccessible = true }
        val danmakuField = holder.getDeclaredField("n").apply { isAccessible = true }
        val bindWard = wardField.type.getDeclaredMethod("a", docker, feedCell, wardListener)
        val bindTopic = holder.getDeclaredMethod("a", docker, feedCell)

        fun supportsWard(context: Any?, cell: Any?, dependencies: Any?): Boolean {
            if (!itemCell.isInstance(cell)) return false
            val provider = context.callMethodOrNull("getDockerDependency", listInfo)
            val listId = provider.callMethodOrNullAs<String>("getListId") ?: return false
            if (!FEED_LIST.matches(listId)) return false
            val item = cell.callMethodOrNull("getFeedItem")
            if (item.callMethodOrNull("getWardInfo") == null) return false
            val origin = dependencies.callMethodOrNull("a", originItem)
            if (origin.callMethodOrNullAs<Boolean>("b", context, cell) == true) return false
            return cell.callMethodOrNullAs<Int>("getCellType") != 23 ||
                item.callMethodOrNull("getCellUICtrl").callMethodOrNullAs<Int>("getDisplayStyle") != 0
        }

        // The native manager also uses this flag to create a topic row on posts without a topic.
        manager.getDeclaredMethod("c", docker, feedCell, dependency).hookAfterMethod { param ->
            if (param.throwable != null || param.result != true) return@hookAfterMethod
            val (context, cell, dependencies) = param.args
            if (supportsWard(context, cell, dependencies)) param.result = false
        }

        bindTopic.hookAfterMethod { param ->
            if (param.throwable != null) return@hookAfterMethod
            val (context, cell) = param.args
            if (param.thisObject.javaClass != holder ||
                !supportsWard(context, cell, dependencyField.get(param.thisObject))
            ) return@hookAfterMethod
            val ward = wardField.get(param.thisObject) as? View ?: return@hookAfterMethod
            val danmaku = danmakuField.get(param.thisObject) as? View ?: return@hookAfterMethod
            // The host puts the ward and danmaku in the same slot, preferring danmaku when both exist.
            if (ward.visibility != View.VISIBLE && danmaku.visibility == View.VISIBLE && placeSideBySide(ward, danmaku)) {
                bindWard.invoke(ward, context, cell, param.thisObject)
                ward.visibility = View.VISIBLE
            }
        }
    }

    private fun placeSideBySide(ward: View, danmaku: View): Boolean {
        if (danmaku.parent !== ward.parent) return false
        if (ward.parent is LinearLayout) return true
        val slot = ward.parent as? FrameLayout ?: return false
        slot.removeView(ward)
        slot.removeView(danmaku)
        val row = LinearLayout(slot.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val gap = (4 * resources.displayMetrics.density).roundToInt()
            addView(danmaku, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(ward, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = gap })
        }
        slot.addView(row, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        return true
    }

    companion object {
        const val KEY = "show_feed_ward_button"
        private val FEED_LIST = Regex("feed_-?\\d+_-?\\d+")
    }
}

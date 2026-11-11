package com.akari.ppx.xp.hook.assist

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import com.akari.ppx.data.AutoSendGod
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.callMethodOrNull
import com.akari.ppx.utils.callMethodOrNullAs
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.utils.showToast
import com.akari.ppx.xp.hook.SwitchHook
import java.util.WeakHashMap

@Suppress("unused")
class AutoSendGodHook : SwitchHook(AutoSendGod.ENABLED) {
    override fun onHook() {
        if (!supportsCommentActions("AutoSendGod")) return
        val adapterClass = hostClass("com.sup.android.m_comment.view.CommentAdapter")
        val dockerContext = adapterClass.getDeclaredField("c").apply { isAccessible = true }
        val fragmentClass = hostClass("com.sup.android.m_comment.view.CommentFragment")
        val commentParams = fragmentClass.getDeclaredField("l").apply { isAccessible = true }
        val commentCell = hostClass("com.sup.android.mi.feed.repo.bean.comment.CommentFeedCell")
        val main = Handler(Looper.getMainLooper())
        val attemptedPosts = mutableSetOf<Long>()
        val firstBatches = WeakHashMap<Any, PendingBatch>()

        val appendComments = adapterClass.getDeclaredMethod("b", List::class.java, Boolean::class.java)
        val findPosition = adapterClass.getDeclaredMethod("a", hostClass("com.sup.superb.dockerbase.misc.ICellData"))
        val commentAt = adapterClass.getDeclaredMethod("c", Int::class.java)
        appendComments.hookAfterMethod { param ->
            if (param.throwable != null || param.args[1] != true) return@hookAfterMethod
            val rows = param.args[0] as? List<*> ?: return@hookAfterMethod
            if (rows.isEmpty()) return@hookAfterMethod
            val adapter = param.thisObject
            val fragment = dockerContext.get(adapter).callMethodOrNull("getFragment")
                ?: return@hookAfterMethod
            val view = fragment.callMethodOrNullAs<View>("getView") ?: return@hookAfterMethod
            val params = commentParams.get(fragment)
            val postId = params.callMethodOrNullAs<Long>("c")?.takeIf { it > 0L }
                ?: return@hookAfterMethod
            if (commentCell.isInstance(params.callMethodOrNull("d"))) return@hookAfterMethod

            val comments = rows.mapNotNull { CommentGodAction.row(it.callMethodOrNull("getCellData")) }
                .distinctBy { it.id }
            if (comments.isEmpty() || comments.any { it.postId != postId }) return@hookAfterMethod
            val batch = PendingBatch(postId, view)

            fun runWhenVisible(retries: Int) {
                runCatching {
                    if (firstBatches[adapter] !== batch) return@runCatching
                    if (postId in attemptedPosts ||
                        !XPrefs<Boolean>(AutoSendGod.ENABLED) ||
                        fragment.callMethodOrNullAs<View>("getView") !== view ||
                        commentParams.get(fragment).callMethodOrNullAs<Long>("c") != postId
                    ) {
                        firstBatches.remove(adapter)
                        return@runCatching
                    }
                    if (!view.isShown || !view.hasWindowFocus() || !view.getGlobalVisibleRect(Rect()) ||
                        fragment.callMethodOrNullAs<Boolean>("isResumed") != true ||
                        fragment.callMethodOrNullAs<Boolean>("getUserVisibleHint") != true
                    ) {
                        if (retries > 0) main.postDelayed({ runWhenVisible(retries - 1) }, 100)
                        else {
                            firstBatches.remove(adapter)
                            Log.d("AutoSendGod skipped: comment panel did not become visible")
                        }
                        return@runCatching
                    }

                    val mode = XPrefs(AutoSendGod.MODE, AutoSendGod.FIRST)
                    val likes = comments.map { it.comment.callMethodOrNullAs<Long>("getLikeCount") }
                    val index = AutoSendGod.selectIndex(likes, mode, XPrefs(AutoSendGod.FLOOR, "1"))
                    firstBatches.remove(adapter)
                    attemptedPosts.add(postId)
                    if (index == null) {
                        view.context.showToast("自动送神 · 首批评论中未找到目标")
                        return@runCatching
                    }
                    val row = comments[index]
                    Log.i("AutoSendGod target mode=$mode floor=${index + 1} rows=${comments.size}")
                    CommentGodAction.request(view, row, automatic = true) {
                        val position = findPosition.invoke(adapter, row.cell) as Int
                        if (position >= 0 && commentAt.invoke(adapter, position) === row.cell) {
                            adapter.callMethodOrNull("notifyItemChanged", position)
                        }
                    }
                }.onFailure(Log::e)
            }

            main.post {
                if (postId !in attemptedPosts && firstBatches[adapter] != batch) {
                    firstBatches[adapter] = batch
                    runWhenVisible(retries = 15)
                }
            }
        }
    }

    private data class PendingBatch(val postId: Long, val view: View)
}

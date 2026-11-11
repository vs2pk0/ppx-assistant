package com.akari.ppx.xp.hook.assist

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import com.akari.ppx.BuildConfig
import com.akari.ppx.data.AutoSendGod
import com.akari.ppx.data.Const.TARGET_APP_ID
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.callMethodOrNull
import com.akari.ppx.utils.callMethodOrNullAs
import com.akari.ppx.utils.callStaticMethod
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.ctx
import com.akari.ppx.xp.hook.SwitchHook
import java.lang.reflect.Proxy
import java.util.WeakHashMap

@Suppress("unused")
class AutoSendGodHook : SwitchHook(AutoSendGod.ENABLED) {
    override fun onHook() {
        val version = ctx.packageManager.getPackageInfo(TARGET_APP_ID, 0).versionName
        // The host has separate obfuscated methods for sending and withdrawing a vote.
        if (version !in setOf("6.5.5", "6.6.1")) {
            Log.w("AutoSendGod unsupported host=$version")
            return
        }

        val adapterClass = hostClass("com.sup.android.m_comment.view.CommentAdapter")
        val dockerContext = adapterClass.getDeclaredField("c").apply { isAccessible = true }
        val fragmentClass = hostClass("com.sup.android.m_comment.view.CommentFragment")
        val commentParams = fragmentClass.getDeclaredField("l").apply { isAccessible = true }
        val commentCell = hostClass("com.sup.android.mi.feed.repo.bean.comment.CommentFeedCell")
        val replyCell = hostClass("com.sup.android.mi.feed.repo.bean.comment.ReplyFeedCell")
        val commentClass = hostClass("com.sup.android.mi.feed.repo.bean.comment.Comment")
        // Read the actual status, even when the manual unlock option alters the getter.
        val status = commentClass.getDeclaredField("sendGodStatus").apply { isAccessible = true }
        val callbackType = hostClass("com.sup.android.m_comment.util.helper.g")
        val helperClass = hostClass("com.sup.android.m_comment.util.helper.d")
        val send = helperClass.getDeclaredMethod("b", Long::class.java, Long::class.java, callbackType)
        val helperInstance = helperClass.getDeclaredField("b")
        val godAccessClass = hostClass("com.sup.android.mi.feed.repo.utils.d")
        // This singleton reads host preferences during class initialization.
        val godAccessInstance = godAccessClass.getDeclaredField("b")
        val userServiceType = hostClass("com.sup.android.mi.usercenter.IUserCenterService")
        val serviceManager = hostClass("com.bytedance.news.common.service.manager.ServiceManager")
        val main = Handler(Looper.getMainLooper())
        val attemptedPosts = mutableSetOf<Long>()
        val processedAdapters = WeakHashMap<Any, Long>()

        val appendComments = adapterClass.getDeclaredMethod("b", List::class.java, Boolean::class.java)
        appendComments.hookAfterMethod { param ->
            if (param.throwable != null || param.args[1] != true) return@hookAfterMethod
            val rows = param.args[0] as? List<*> ?: return@hookAfterMethod
            if (rows.isEmpty()) return@hookAfterMethod
            val adapter = param.thisObject
            val fragment = dockerContext.get(adapter).callMethodOrNull("getFragment")
                ?: return@hookAfterMethod
            val view = fragment.callMethodOrNullAs<View>("getView") ?: return@hookAfterMethod
            val comments = rows.mapNotNull { row ->
                val cell = row.callMethodOrNull("getCellData")
                if (cell == null || !commentCell.isInstance(cell) || replyCell.isInstance(cell)) {
                    return@mapNotNull null
                }
                val comment = cell.callMethodOrNull("getComment") ?: return@mapNotNull null
                val id = comment.callMethodOrNullAs<Long>("getCommentId") ?: return@mapNotNull null
                if (id > 0L) CommentRow(cell, comment, id) else null
            }.distinctBy { it.id }
            if (comments.isEmpty()) return@hookAfterMethod

            // Run after the host applies the first batch and finishes updating the UI.
            view.post {
                runCatching {
                    if (!view.isShown || !view.hasWindowFocus() || !view.getGlobalVisibleRect(Rect()) ||
                        fragment.callMethodOrNullAs<Boolean>("isResumed") != true ||
                        fragment.callMethodOrNullAs<Boolean>("getUserVisibleHint") != true ||
                        !XPrefs<Boolean>(AutoSendGod.ENABLED)
                    ) return@runCatching

                    val params = commentParams.get(fragment)
                    val postId = params.callMethodOrNullAs<Long>("c")
                        ?.takeIf { it > 0L } ?: return@runCatching
                    if (commentCell.isInstance(params.callMethodOrNull("d")) ||
                        comments.any { it.comment.callMethodOrNullAs<Long>("getRootCellId") != postId } ||
                        postId in attemptedPosts || processedAdapters.put(adapter, postId) == postId
                    ) return@runCatching

                    val mode = XPrefs(AutoSendGod.MODE, AutoSendGod.FIRST)
                    val likes = comments.map { it.comment.callMethodOrNullAs<Long>("getLikeCount") }
                    val index = AutoSendGod.selectIndex(likes, mode, XPrefs(AutoSendGod.FLOOR, "1"))
                    if (index == null) {
                        Log.d("AutoSendGod no target mode=$mode rows=${comments.size}")
                        return@runCatching
                    }
                    val (cell, comment, commentId) = comments[index]
                    val users = serviceManager.callStaticMethod("getService", userServiceType)
                    val author = comment.callMethodOrNull("getUserInfo")
                        .callMethodOrNullAs<Long>("getId")?.takeIf { it > 0L }
                    val loggedIn = users.callMethodOrNullAs<Boolean>("hasLogin") == true
                    val ownComment = author?.let { users.callMethodOrNullAs<Boolean>("isSelf", it) }
                    val rawStatus = status.getInt(comment)
                    val access = godAccessInstance.get(null).callMethodOrNullAs<Int>("a")
                    val publishStatus = cell.callMethodOrNullAs<Int>("getPublishStatus")
                    if (BuildConfig.DEBUG) {
                        Log.i("AutoSendGod preview mode=$mode floor=${index + 1} rows=${comments.size}" +
                            " likes=${likes[index]} status=$rawStatus access=$access login=$loggedIn" +
                            " own=$ownComment publish=$publishStatus")
                        attemptedPosts.add(postId)
                        return@runCatching
                    }
                    if (!loggedIn || ownComment != false || rawStatus != 1 || access != 1 ||
                        (publishStatus != 0 && publishStatus != 2 && publishStatus != 3)
                    ) return@runCatching

                    val callback = Proxy.newProxyInstance(cl, arrayOf(callbackType)) { proxy, method, args ->
                        when (method.name) {
                            "a" -> {
                                val result = args?.firstOrNull()
                                val newStatus = if (result.callMethodOrNullAs<Boolean>("isSuccess") == true) {
                                    result.callMethodOrNull("getData").callMethodOrNullAs<Int>("getSendGodStatus")
                                } else null
                                Log.i("AutoSendGod result status=${newStatus ?: "unconfirmed"}")
                                if (newStatus != null && newStatus in 0..3) main.post {
                                    runCatching update@{
                                        if (status.getInt(comment) != 1) return@update
                                        status.setInt(comment, newStatus)
                                        val position = adapter.callMethodOrNullAs<Int>("a", commentId)
                                        if (position != null && position >= 0) {
                                            adapter.callMethodOrNull("notifyItemChanged", position)
                                        }
                                    }.onFailure(Log::e)
                                }
                                null
                            }
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.firstOrNull()
                            "toString" -> "AutoSendGodCallback"
                            else -> null
                        }
                    }
                    attemptedPosts.add(postId)
                    send.invoke(helperInstance.get(null), postId, commentId, callback)
                }.onFailure(Log::e)
            }
        }
    }

    private fun hostClass(name: String): Class<*> = Class.forName(name, false, cl)

    private data class CommentRow(val cell: Any, val comment: Any, val id: Long)
}

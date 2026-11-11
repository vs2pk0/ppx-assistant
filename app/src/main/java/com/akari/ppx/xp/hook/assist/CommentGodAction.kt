package com.akari.ppx.xp.hook.assist

import android.os.Handler
import android.os.Looper
import android.view.View
import com.akari.ppx.BuildConfig
import com.akari.ppx.data.CommentGod
import com.akari.ppx.data.Const.TARGET_APP_ID
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.callMethodOrNull
import com.akari.ppx.utils.callMethodOrNullAs
import com.akari.ppx.utils.callStaticMethod
import com.akari.ppx.utils.showToast
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.ctx
import java.lang.reflect.Proxy

internal object CommentGodAction {
    private val commentCell by lazy { hostClass("com.sup.android.mi.feed.repo.bean.comment.CommentFeedCell") }
    private val replyCell by lazy { hostClass("com.sup.android.mi.feed.repo.bean.comment.ReplyFeedCell") }
    private val statusField by lazy {
        // Read the actual status even when the manual unlock option alters the getter.
        hostClass("com.sup.android.mi.feed.repo.bean.comment.Comment").getDeclaredField("sendGodStatus")
            .apply { isAccessible = true }
    }
    private val callbackType by lazy { hostClass("com.sup.android.m_comment.util.helper.g") }
    private val helperClass by lazy { hostClass("com.sup.android.m_comment.util.helper.d") }
    private val send by lazy {
        helperClass.getDeclaredMethod("b", Long::class.java, Long::class.java, callbackType)
    }
    private val helperInstance by lazy { helperClass.getDeclaredField("b") }
    private val accessInstance by lazy {
        hostClass("com.sup.android.mi.feed.repo.utils.d").getDeclaredField("b")
    }
    private val userServiceType by lazy { hostClass("com.sup.android.mi.usercenter.IUserCenterService") }
    private val serviceManager by lazy { hostClass("com.bytedance.news.common.service.manager.ServiceManager") }
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableMapOf<Long, Runnable>()

    fun row(cell: Any?): Row? {
        if (!commentCell.isInstance(cell) || replyCell.isInstance(cell)) return null
        val comment = cell.callMethodOrNull("getComment") ?: return null
        val id = comment.callMethodOrNullAs<Long>("getCommentId")?.takeIf { it > 0 } ?: return null
        val postId = comment.callMethodOrNullAs<Long>("getRootCellId")?.takeIf { it > 0 } ?: return null
        return Row(requireNotNull(cell), comment, id, postId)
    }

    fun status(row: Row): Int = statusField.getInt(row.comment)

    fun request(view: View, row: Row, automatic: Boolean, onResult: () -> Unit = {}) {
        val prefix = if (automatic) "自动送神 · " else ""
        fun show(message: String) {
            if (view.isShown && view.hasWindowFocus()) view.context.showToast(prefix + message)
        }

        runCatching {
            if (row.id in pending) {
                if (!automatic) show("正在送神，请稍候")
                return
            }
            val users = serviceManager.callStaticMethod("getService", userServiceType)
            val author = row.comment.callMethodOrNull("getUserInfo")
                .callMethodOrNullAs<Long>("getId")?.takeIf { it > 0 }
            val blocked = CommentGod.blockedReason(
                loggedIn = users.callMethodOrNullAs<Boolean>("hasLogin") == true,
                own = author?.let { users.callMethodOrNullAs<Boolean>("isSelf", it) },
                status = status(row),
                access = accessInstance.get(null).callMethodOrNullAs<Int>("a"),
                published = row.cell.callMethodOrNullAs<Int>("getPublishStatus")
            )
            if (blocked != null) {
                Log.i("CommentGod skipped automatic=$automatic reason=$blocked")
                show(blocked)
                return
            }
            if (BuildConfig.DEBUG) {
                Log.i("CommentGod preview automatic=$automatic")
                show("调试预览，已选中可送神评论")
                return
            }

            val timeout = Runnable {
                pending.remove(row.id)
                Log.w("CommentGod timed out automatic=$automatic")
                show("送神结果未确认，请稍后刷新")
            }
            val callback = Proxy.newProxyInstance(cl, arrayOf(callbackType)) { proxy, method, args ->
                when (method.name) {
                    "a" -> {
                        val result = args?.firstOrNull()
                        val newStatus = if (result.callMethodOrNullAs<Boolean>("isSuccess") == true) {
                            result.callMethodOrNull("getData").callMethodOrNullAs<Int>("getSendGodStatus")
                        } else null
                        main.post {
                            if (pending[row.id] !== timeout) return@post
                            pending.remove(row.id)
                            main.removeCallbacks(timeout)
                            Log.i("CommentGod result automatic=$automatic status=${newStatus ?: "unconfirmed"}")
                            show(CommentGod.resultMessage(newStatus))
                            runCatching {
                                if (newStatus != null && newStatus in 0..3 && status(row) == 1) {
                                    statusField.setInt(row.comment, newStatus)
                                    onResult()
                                }
                            }.onFailure(Log::e)
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "CommentGodCallback"
                    else -> null
                }
            }
            pending[row.id] = timeout
            main.postDelayed(timeout, 30_000L)
            send.invoke(helperInstance.get(null), row.postId, row.id, callback)
        }.onFailure {
            pending.remove(row.id)?.let(main::removeCallbacks)
            Log.e(it)
            show(CommentGod.resultMessage(null))
        }
    }

    data class Row(val cell: Any, val comment: Any, val id: Long, val postId: Long)
}

internal fun hostClass(name: String): Class<*> = Class.forName(name, false, cl)

internal fun supportsCommentActions(feature: String): Boolean {
    val version = ctx.packageManager.getPackageInfo(TARGET_APP_ID, 0).versionName
    // The reflection signatures below are verified on these host versions.
    return (version == "6.5.5" || version == "6.6.1").also {
        if (!it) Log.w("$feature unsupported host=$version")
    }
}

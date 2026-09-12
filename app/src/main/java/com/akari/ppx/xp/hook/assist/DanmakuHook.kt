@file:Suppress("unused")

package com.akari.ppx.xp.hook.assist

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.asyncCallback
import com.akari.ppx.xp.Init.asyncCallbackClass
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook
import java.lang.reflect.Proxy

class DanmakuHook : BaseHook {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onHook() {
        val (isUnlockDanmaku, isQuerySender) = listOf<Boolean>(
            XPrefs("unlock_danmaku"),
            XPrefs("query_danmaku_sender")
        )
        isUnlockDanmaku.check(true) {
            "com.sup.android.mi.usercenter.model.UserInfo".hookAfterMethod(
                cl,
                "getUserPrivilege"
            ) { param ->
                val privilege = param.result ?: "com.sup.android.mi.usercenter.model.UserInfo\$UserPrivilege".findClass(cl).new()
                privilege.setBooleanField("canSendAdvanceDanmaku", true)
                param.result = privilege
            }
        }
        isQuerySender.check(true) {
            val touchHelperClass = "com.sup.android.m_danmaku.widget.j".findClass(cl)
            val baseDanmakuClass = "com.sup.android.m_danmaku.danmaku.model.BaseDanmaku".findClass(cl)
            Log.i("DanmakuHook install query sender")
            touchHelperClass.hookBeforeAllMethods("b") { param ->
                if (param.args.size != 3) return@hookBeforeAllMethods
                val danmaku = param.args[0] ?: return@hookBeforeAllMethods
                if (!baseDanmakuClass.isAssignableFrom(danmaku.javaClass)) {
                    return@hookBeforeAllMethods
                }
                val userId = extractUserId(danmaku)
                if (userId <= 0L) {
                    Log.i("DanmakuHook skip invalid userId=$userId")
                    return@hookBeforeAllMethods
                }
                val context = extractContext(param.thisObjectOrNull)
                if (context == null) {
                    Log.i("DanmakuHook skip null context userId=$userId")
                    return@hookBeforeAllMethods
                }
                Log.i("DanmakuHook open profile userId=$userId")
                openUserProfile(context, userId)
                param.result = true
            }
        }
    }

    private fun extractContext(helper: Any?): Context? =
        helper?.getObjectFieldOrNull("b")?.callMethodOrNullAs<Context>("getContext")

    internal fun extractUserId(danmaku: Any): Long =
        listOf("V", "userId", "uid").firstNotNullOfOrNull {
            danmaku.getLongFieldOrNull(it)?.takeIf { id -> id > 0L }
        } ?: 0L

    private fun openUserProfile(context: Context, userId: Long) {
        mainHandler.post {
            if (openDirectProfile(context, userId)) {
                return@post
            }
            openProfileSchemaFallback(context, userId)
        }
    }

    private fun openDirectProfile(context: Context, userId: Long): Boolean = runCatching {
        val route = "com.bytedance.router.SmartRouter".findClass(cl)
            .callStaticMethod("buildRoute", context, "//user/profile") ?: return@runCatching false
        route.callMethod("withParam", "user_id", userId)
        route.callMethod("open")
        true
    }.onFailure {
        Log.i("DanmakuHook direct route failed userId=$userId")
        Log.e(it)
    }.getOrDefault(false)

    private fun openProfileSchemaFallback(context: Context, userId: Long) {
        val callbackInterface = runCatching {
            "com.sup.android.mi.usercenter.a".findClass(cl)
        }.getOrElse {
            asyncCallbackClass
        } ?: run {
            Log.i("DanmakuHook missing callback interface userId=$userId")
            return
        }
        val callbackMethodName = runCatching { asyncCallback() }.getOrDefault("callback")
        val callback = Proxy.newProxyInstance(
            cl,
            arrayOf(callbackInterface)
        ) { _, method, args ->
            if (method.name != callbackMethodName && method.name != "callback") {
                return@newProxyInstance defaultProxyValue(method.returnType)
            }
            val result = args?.firstOrNull() ?: return@newProxyInstance null
            val profileSchema = result.callMethodOrNull("getData")
                ?.callMethodOrNullAs<String>("getProfileSchema")
            if (profileSchema.isNullOrBlank()) {
                Log.i("DanmakuHook empty profileSchema userId=$userId")
                return@newProxyInstance null
            }
            mainHandler.post {
                runCatching {
                    "com.bytedance.router.SmartRouter".findClass(cl)
                        .callStaticMethod("buildRoute", context, profileSchema)
                        ?.callMethod("open")
                }.onFailure(Log::e)
            }
            null
        }
        runCatching {
            "com.sup.android.module.usercenter.core.e".findClass(cl)
                .new()
                .callMethod("a", userId, callback)
        }.onFailure {
            Log.i("DanmakuHook fallback query failed userId=$userId")
            Log.e(it)
        }
    }

    private fun defaultProxyValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }
}

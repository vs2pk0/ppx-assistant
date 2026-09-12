@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import android.app.Activity
import android.content.Context
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.*
import com.akari.ppx.xp.Init.absFeedCellClass
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.Init.shareViewClass
import com.akari.ppx.xp.Init.videoDownloadConfigClass
import com.akari.ppx.xp.hook.assist.AudioHook
import com.akari.ppx.xp.hook.SwitchHook

class ShareHook : SwitchHook("simplify_share") {
    override fun onHook() {
        shareViewClass?.hookBeforeConstructor(
            Context::class.java,
            "[Lcom.sup.android.i_sharecontroller.model.c;",
            "[Lcom.sup.android.i_sharecontroller.model.OptionAction\$OptionActionType;",
            "com.sup.android.i_sharecontroller.model.OptionAction\$a",
            absFeedCellClass
        ) { param ->
            param.args[1] = null
        }
        "com.sup.android.m_sharecontroller.service.BaseShareService".replaceMethod(
            cl,
            "getAllShareletTypes",
            Context::class.java,
            Int::class.java
        ) { emptyList<Any>() }
        videoDownloadConfigClass?.let { configClass ->
            configClass.declaredMethods.firstOrNull {
                it.name in setOf("getN", "n") && it.parameterTypes.isEmpty() &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }?.replaceMethod { false }
        }
        "com.sup.android.uikit.VideoDownloadProgressActivity".findClass(cl).apply {
            declaredMethods.find { m ->
                m.parameterTypes.size == 3 &&
                    m.parameterTypes.all { it == Boolean::class.javaPrimitiveType }
            }?.name?.let { methodName ->
                hookAfterMethod(
                    methodName,
                    Boolean::class.java,
                    Boolean::class.java,
                    Boolean::class.java
                ) { param ->
                    param.args[1].check(true) {
                        if (AudioHook.shouldSuppressHostToast()) {
                            AudioHook.clearHostToastSuppression()
                            (param.thisObject as Activity).finish()
                            return@check
                        }
                        showStickyToast("视频保存成功")
                        (param.thisObject as Activity).finish()
                    }
                }
            }
        }
    }
}

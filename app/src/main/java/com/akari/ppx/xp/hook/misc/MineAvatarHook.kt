package com.akari.ppx.xp.hook.misc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.callMethod
import com.akari.ppx.utils.callMethodOrNullAs
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.getLongFieldOrNull
import com.akari.ppx.utils.getObjectFieldOrNull
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook
import java.util.WeakHashMap

/** 我的页刷新会更新头像请求地址；加载期间保留同一账号的现有图片，避免闪现默认头像。 */
class MineAvatarHook : BaseHook {
    override fun onHook() {
        val users = WeakHashMap<View, Long>()
        "com.sup.android.uikit.image.FrescoHelper".findClass(cl).hookBeforeMethod(
            "load", "com.facebook.drawee.view.SimpleDraweeView", "com.ss.android.image.IImageInfo"
        ) { param ->
            val avatar = param.args[0] as? View ?: return@hookBeforeMethod
            if (avatar.javaClass.name != "com.sup.android.uikit.avatar.AvatarView") return@hookBeforeMethod
            var ancestor = avatar.parent
            while (ancestor is View &&
                ancestor.javaClass.name != "com.sup.android.m_mine.view.subview.MyProfileHeaderLayout") {
                ancestor = ancestor.parent
            }
            if (ancestor !is View) return@hookBeforeMethod
            val userField = ancestor.javaClass.declaredFields.firstOrNull {
                it.type.name == "com.sup.android.mi.usercenter.model.UserInfo"
            } ?: return@hookBeforeMethod
            val userId = ancestor.getObjectFieldOrNull(userField.name)?.getLongFieldOrNull("id")
                ?.takeIf { it > 0 } ?: return@hookBeforeMethod
            val hierarchy = avatar.callMethodOrNullAs<Any>("getHierarchy") ?: return@hookBeforeMethod
            val previousUser = users.put(avatar, userId)
            hierarchy.callMethod("setFadeDuration", 0)
            if (previousUser != null && previousUser != userId) {
                // 账号切换时不沿用上一个账号的图片。
                hierarchy.callMethod("setPlaceholderImage", ColorDrawable(Color.TRANSPARENT))
            } else if (previousUser == userId && param.args[1] != null &&
                hierarchy.callMethodOrNullAs<Boolean>("hasImage") == true &&
                avatar.width > 0 && avatar.height > 0) {
                val drawable = hierarchy.callMethodOrNullAs<Drawable>("getTopLevelDrawable")
                    ?: return@hookBeforeMethod
                val bitmap = Bitmap.createBitmap(avatar.width, avatar.height, Bitmap.Config.ARGB_8888)
                bitmap.density = avatar.resources.displayMetrics.densityDpi
                drawable.draw(Canvas(bitmap))
                hierarchy.callMethod("setPlaceholderImage", BitmapDrawable(avatar.resources, bitmap))
                Log.d("MineAvatarHook retain avatar while refreshing")
            }
        }
    }
}

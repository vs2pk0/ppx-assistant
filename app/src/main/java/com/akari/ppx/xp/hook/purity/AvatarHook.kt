@file:Suppress("unused")

package com.akari.ppx.xp.hook.purity

import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.SwitchHook

class AvatarHook : SwitchHook("remove_avatar_decoration") {
    override fun onHook() {
        // 只在绘制层过滤，并且走 View 自带的开关。
        // 旧做法是渲染前把装饰 List 换成 emptyList，宿主会把它当成
        // “装饰数据还没回来”，重新拉取并重绑 header，挂饰 Lottie 于是
        // 从第 0 帧重新播放，肉眼就是头像一闪一闪。
        "com.sup.android.uikit.avatar.FrameAvatarView".findClass(cl)
            .hookBeforeMethod("setDecorationViewVisible", Boolean::class.javaPrimitiveType) { param ->
                param.args[0] = false
            }
    }
}

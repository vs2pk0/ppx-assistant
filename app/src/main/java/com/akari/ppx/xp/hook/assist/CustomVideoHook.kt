package com.akari.ppx.xp.hook.assist

import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.CommentToolbar
import com.akari.ppx.utils.CustomVideos
import com.akari.ppx.xp.hook.BaseHook

class CustomVideoHook : BaseHook {
    override fun onHook() {
        if (!XPrefs<Boolean>("enable_custom_video")) return
        CommentToolbar.onCreate { dialog ->
            CommentToolbar.attach(dialog, "ppx_custom_video", CommentToolbar.Icon.VIDEOS, 2,
                "自定义视频") { CustomVideos.show(dialog) }
        }
    }
}

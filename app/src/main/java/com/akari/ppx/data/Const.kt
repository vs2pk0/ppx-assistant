package com.akari.ppx.data

import com.akari.ppx.data.model.ChannelItem

object Const {
    const val APP_NAME = "皮皮虾助手"
    const val TARGET_APP_ID = "com.sup.android.superb"
    const val PREFS_NAME = "settings"
    const val CACHE_NAME = ".cache620v2"
    const val CP_URI = "content://com.akari.ppx.CP/"
    const val TAB_SCHEMA = "akari://open_zs"
    const val GIT_PAGE_URI = "https://github.com/vs2pk0/ppx-assistant"
    const val CHANNEL_KEY = "channels"
    const val ALLOW_STARTUP_HINT = "请给予助手后台运行权限，否则功能将不会生效"
    const val UNINSTALL_HINT = "请卸载第三方模块后重新启动"
    val CATEGORY_NAMES = arrayOf("关注", "推荐", "视频", "虾聊", "颜值", "真香", "汽车", "图片", "文字", "游戏", "小康")
    val CATEGORY_TYPES = arrayOf(2, 1, 4, 52, 48, 27, 28, 10, 11, 16, 111)
    val CHANNEL_DEFAULT = CATEGORY_NAMES.zip(CATEGORY_TYPES).map {
        ChannelItem(
            name = it.first,
            type = it.second,
            checked = true
        )
    }
}

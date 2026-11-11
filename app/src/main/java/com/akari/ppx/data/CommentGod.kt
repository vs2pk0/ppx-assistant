package com.akari.ppx.data

internal object CommentGod {
    const val BUTTON = "show_comment_god_button"

    fun blockedReason(loggedIn: Boolean, own: Boolean?, status: Int, access: Int?, published: Int?): String? = when {
        status == 2 -> "这条评论已送神"
        status == 3 -> "这条评论的神评已送满"
        !loggedIn -> "请先登录"
        own == true -> "不能给自己的评论送神"
        access != 1 -> "当前账号没有送神资格"
        own == null || status != 1 || (published != 0 && published != 2 && published != 3) -> "这条评论暂不可送神"
        else -> null
    }

    fun resultMessage(status: Int?): String = when (status) {
        2 -> "送神成功"
        3 -> "这条评论的神评已送满"
        else -> "送神未成功，请稍后重试"
    }
}

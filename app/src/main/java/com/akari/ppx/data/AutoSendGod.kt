package com.akari.ppx.data

internal object AutoSendGod {
    const val ENABLED = "auto_send_god_on_open"
    const val MODE = "auto_send_god_target"
    const val FLOOR = "auto_send_god_floor"

    const val FIRST = "first"
    const val MOST_LIKED = "most_liked"
    const val SPECIFIED = "specified"

    private const val GOD_COMMENT_LIMIT = 2

    fun hasEnoughGodComments(ids: Sequence<Long>): Boolean =
        ids.filter { it > 0 }.distinct().take(GOD_COMMENT_LIMIT).count() == GOD_COMMENT_LIMIT

    fun selectIndex(likes: List<Long?>, mode: String, floor: String): Int? {
        val index = when (mode) {
            FIRST -> 0
            MOST_LIKED -> {
                if (likes.any { it == null || it < 0 }) return null
                likes.indices.maxByOrNull { likes[it] ?: 0L } ?: return null
            }
            SPECIFIED -> floor.toIntOrNull()?.takeIf { it > 0 }?.minus(1) ?: return null
            else -> return null
        }
        return index.takeIf { it in likes.indices }
    }
}

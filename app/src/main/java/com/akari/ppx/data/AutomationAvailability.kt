package com.akari.ppx.data

/** Legacy automation is unavailable until its host flows are verified end to end. */
object AutomationAvailability {
    val disabledKeys = setOf(
        "auto_browse", "auto_browse_frequency", "video_delay_handoff",
        "auto_digg", "digg_pause_after_frequent",
        "auto_diss", "diss_pause_after_frequent",
        "auto_comment", "comment_text", "auto_comment_condition",
        "auto_comment_digg", "auto_ward", "auto_ward_condition",
        "auto_send_god", "auto_send_god_time_limit", "modify_share_counts"
    )

    fun isSettingAvailable(key: String) = key !in disabledKeys

    fun isHookAvailable(className: String) =
        !className.startsWith("com.akari.ppx.xp.hook.auto.")
}

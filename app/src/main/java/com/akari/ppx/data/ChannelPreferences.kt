package com.akari.ppx.data

import com.akari.ppx.data.Const.CHANNEL_DEFAULT
import com.akari.ppx.data.Const.MERGED_MEDIA_CHANNEL
import com.akari.ppx.data.model.ChannelItem
import com.akari.ppx.utils.fromJsonList

internal fun readChannelPreferences(raw: String?): List<ChannelItem> {
    val saved = runCatching { raw.fromJsonList<ChannelItem?>().filterNotNull() }.getOrNull()
    if (saved.isNullOrEmpty()) return CHANNEL_DEFAULT.map { it.copy() }
    if (saved.any { it.type == MERGED_MEDIA_CHANNEL }) return saved

    // Hosts that dropped the separate image/text feeds now provide a single 图文 feed.
    val legacyPosition = saved.indexOfFirst { it.checked && isLegacyMediaChannel(it.type) }
    return saved.toMutableList().apply {
        add(
            if (legacyPosition >= 0) legacyPosition else size,
            ChannelItem(name = "图文", type = MERGED_MEDIA_CHANNEL, checked = legacyPosition >= 0)
        )
    }
}

internal fun isLegacyMediaChannel(id: Int) = id == 10 || id == 11

internal fun <T : Any> selectHomeChannels(
    available: List<T>,
    preferences: List<ChannelItem>,
    id: (T) -> Int?,
    name: (T) -> String?
): List<T> {
    val selected = preferences.asSequence()
        .filter { it.checked }
        .mapNotNull { preference ->
            available.firstOrNull { id(it) == preference.type }
                ?: available.firstOrNull { name(it) == preference.name }
        }.distinct().toList()
    return selected.ifEmpty { available }
}

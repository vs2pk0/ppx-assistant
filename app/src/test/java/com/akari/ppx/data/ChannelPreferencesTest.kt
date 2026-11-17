package com.akari.ppx.data

import com.akari.ppx.data.model.ChannelItem
import com.akari.ppx.utils.toJson
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelPreferencesTest {
    private val recommend = ChannelItem("推荐", 1)
    private val video = ChannelItem("视频", 4)
    private val unified = ChannelItem("图文", 5)
    private val images = ChannelItem("图片", 10)
    private val text = ChannelItem("文字", 11)
    private val follow = ChannelItem("关注", 2)

    @Test fun migratesLegacySelectionsInTheirSavedOrder() {
        assertEquals(
            listOf(video, unified, recommend),
            select(listOf(follow, recommend, video, unified), listOf(video, images, text, recommend))
        )
    }

    @Test fun retainsSeparateMediaChannelsWhenAvailable() {
        assertEquals(
            listOf(recommend, text, images),
            select(listOf(recommend, images, text, follow), listOf(recommend, text, images))
        )
    }

    @Test fun respectsAnExplicitlyUncheckedUnifiedChannel() {
        assertEquals(
            listOf(recommend),
            select(listOf(recommend, unified), listOf(recommend, images, unified.copy(checked = false)))
        )
    }

    @Test fun keepsHostTabsIfNoneOfTheSelectionsAreAvailable() {
        val available = listOf(follow, recommend)
        assertEquals(available, select(available, listOf(images, text)))
    }

    @Test fun ignoresNullEntriesInSavedPreferences() {
        val saved = readChannelPreferences(listOf(null, recommend).toJson())
        val available = listOf(follow, recommend, unified)
        assertEquals(listOf(recommend), selectHomeChannels(available, saved, ChannelItem::type, ChannelItem::name))
    }

    private fun select(available: List<ChannelItem>, saved: List<ChannelItem>) =
        selectHomeChannels(available, readChannelPreferences(saved.toJson()), ChannelItem::type, ChannelItem::name)
}

package com.akari.ppx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSendGodTest {
    private val likes = listOf(2L, 15L, 15L, 4L)

    @Test fun selectsFirstAndSpecifiedFloorsInDisplayOrder() {
        assertEquals(0, AutoSendGod.selectIndex(likes, AutoSendGod.FIRST, "4"))
        assertEquals(3, AutoSendGod.selectIndex(likes, AutoSendGod.SPECIFIED, "4"))
    }

    @Test fun selectsHighestLikeCountAndBreaksTiesByDisplayOrder() {
        assertEquals(1, AutoSendGod.selectIndex(likes, AutoSendGod.MOST_LIKED, "1"))
    }

    @Test fun skipsMissingTargetsAndInvalidSettings() {
        assertNull(AutoSendGod.selectIndex(emptyList(), AutoSendGod.FIRST, "1"))
        assertNull(AutoSendGod.selectIndex(listOf(2L, null), AutoSendGod.MOST_LIKED, "1"))
        assertNull(AutoSendGod.selectIndex(likes, "invalid", "1"))
        listOf("0", "-1", "5", "abc", "9999999999").forEach {
            assertNull(AutoSendGod.selectIndex(likes, AutoSendGod.SPECIFIED, it))
        }
    }
}

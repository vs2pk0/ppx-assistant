package com.akari.ppx.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure merge semantics shared by clipboard import and the group sheet. */
class EmoticonGroupsTest {
    private fun emoji(uri: String) = CustomEmoticon(uri, 10, 10, false)

    @Test fun mergeAddsNewGroupsAndDedupesByUri() {
        val target = mutableListOf(EmoticonGroup("A", mutableListOf(emoji("u1"))))
        val added = EmoticonGroups.merge(
            target,
            listOf(
                EmoticonGroup("A", mutableListOf(emoji("u1"), emoji("u2"))),
                EmoticonGroup("B", mutableListOf(emoji("u3")))
            )
        )
        assertEquals(2, added)
        assertEquals(listOf("u1", "u2"), target.first { it.name == "A" }.emojis.map { it.uri })
        assertEquals(listOf("u3"), target.first { it.name == "B" }.emojis.map { it.uri })
    }

    @Test fun mergeIgnoresBlankNamesAndBlankUris() {
        val target = mutableListOf<EmoticonGroup>()
        val added = EmoticonGroups.merge(
            target,
            listOf(
                null,
                EmoticonGroup("", mutableListOf(emoji("u1"))),
                EmoticonGroup("B", mutableListOf(emoji(" "), emoji("u2")))
            )
        )
        assertEquals(1, added)
        assertEquals(1, target.size)
        assertEquals("B", target.single().name)
    }

    @Test fun mergeIsIdempotentForRepeatedImports() {
        val target = mutableListOf<EmoticonGroup>()
        val payload = listOf(EmoticonGroup("A", mutableListOf(emoji("u1"), emoji("u2"))))
        assertEquals(2, EmoticonGroups.merge(target, payload))
        assertEquals(0, EmoticonGroups.merge(target, payload))
        assertTrue(target.single().emojis.size == 2)
    }

    @Test fun favoriteImportEnrichesLocalCopyWithoutDuplicatingOrReplacingMetadata() {
        val target = mutableListOf(EmoticonGroup("收藏表情", mutableListOf(CustomEmoticon("u1", 20, 30, true))))
        val payload = listOf(EmoticonGroup("收藏表情", mutableListOf(CustomEmoticon("u1", local = "/cached/a.gif"))))
        assertEquals(0, EmoticonGroups.merge(target, payload))
        assertEquals(CustomEmoticon("u1", 20, 30, true, "/cached/a.gif"), target.single().emojis.single())
        assertEquals(0, EmoticonGroups.merge(target, payload))
    }

    @Test fun largeFavoriteImportDeduplicatesAndPreservesOtherGroups() {
        val other = EmoticonGroup("常用", mutableListOf(emoji("keep")))
        val target = mutableListOf(other)
        val favorites = (0 until 2000).flatMap { listOf(emoji("u$it"), emoji("u$it")) }.toMutableList()
        favorites.add(emoji(" "))
        val incoming = listOf(EmoticonGroup("收藏表情", favorites))
        assertEquals(2000, EmoticonGroups.merge(target, incoming))
        assertEquals(0, EmoticonGroups.merge(target, incoming))
        assertEquals(listOf(emoji("keep")), target.first().emojis)
        assertEquals((0 until 2000).map { "u$it" }, target.last().emojis.map { it.uri })
    }

    @Test fun importKeepsExistingLocalCopy() {
        val target = mutableListOf(EmoticonGroup("收藏表情", mutableListOf(CustomEmoticon("u1", local = "/existing.gif"))))
        EmoticonGroups.merge(target, listOf(EmoticonGroup("收藏表情", mutableListOf(CustomEmoticon("u1", local = "/new.gif")))))
        assertEquals("/existing.gif", target.single().emojis.single().local)
    }

    @Test fun movePreservesMetadataAndLeavesOriginalSnapshotUntouched() {
        val moving = CustomEmoticon("u1", 320, 240, true, "/cached/a.gif")
        val original = listOf(
            EmoticonGroup("A", mutableListOf(moving, emoji("keep"))),
            EmoticonGroup("B", mutableListOf(emoji("b1"))),
            EmoticonGroup("C", mutableListOf(emoji("c1")))
        )
        val before = EmoticonGroups.encode(original)
        val moved = EmoticonGroups.movedGroups(original, "A", "B", "u1")!!
        assertEquals(listOf(emoji("keep")), moved[0].emojis)
        assertEquals(listOf(emoji("b1"), moving), moved[1].emojis)
        assertEquals(original[2], moved[2])
        assertEquals(before, EmoticonGroups.encode(original))
        assertEquals(moved, EmoticonGroups.decode(EmoticonGroups.encode(moved)))
    }

    @Test fun moveDeduplicatesAndEnrichesTargetWhileKeepingEmptySourceGroup() {
        val original = listOf(
            EmoticonGroup("A", mutableListOf(CustomEmoticon("u1", local = "/cached/a.gif"))),
            EmoticonGroup("B", mutableListOf(CustomEmoticon("u1", 40, 50, true)))
        )
        val moved = EmoticonGroups.movedGroups(original, "A", "B", "u1")!!
        assertEquals(listOf("A", "B"), moved.map { it.name })
        assertTrue(moved[0].emojis.isEmpty())
        assertEquals(listOf(CustomEmoticon("u1", 40, 50, true, "/cached/a.gif")), moved[1].emojis)
        assertEquals(1, original[0].emojis.size)
        assertNull(original[1].emojis.single().local)
    }

    @Test fun moveRejectsInvalidDestinationsAndMissingEmoticons() {
        val original = listOf(EmoticonGroup("A", mutableListOf(emoji("u1"))), EmoticonGroup("B"))
        val before = EmoticonGroups.encode(original)
        assertNull(EmoticonGroups.movedGroups(original, "A", "A", "u1"))
        assertNull(EmoticonGroups.movedGroups(original, "A", "missing", "u1"))
        assertNull(EmoticonGroups.movedGroups(original, "missing", "B", "u1"))
        assertNull(EmoticonGroups.movedGroups(original, "A", "B", "missing"))
        assertNull(EmoticonGroups.movedGroups(original, "A", "B", " "))
        assertEquals(before, EmoticonGroups.encode(original))
    }

    @Test fun moveWorksInBothDirectionsWithoutDuplicates() {
        val original = listOf(EmoticonGroup("A", mutableListOf(emoji("u1"))), EmoticonGroup("B"))
        val moved = EmoticonGroups.movedGroups(original, "A", "B", "u1")!!
        val restored = EmoticonGroups.movedGroups(moved, "B", "A", "u1")!!
        assertEquals(original, restored)
        assertEquals(1, restored.sumOf { it.emojis.size })
    }

    @Test fun encodeUsesStableFieldNames() {
        val json = EmoticonGroups.encode(
            listOf(EmoticonGroup("A", mutableListOf(CustomEmoticon("u1", 2, 3, true, "/tmp/a.png"))))
        )
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"emojis\""))
        assertTrue(json.contains("\"local\""))
        assertEquals(
            listOf(EmoticonGroup("A", mutableListOf(CustomEmoticon("u1", 2, 3, true, "/tmp/a.png")))),
            EmoticonGroups.decode(json)
        )
    }

    @Test fun decodeAcceptsObfuscatedLegacyKeys() {
        val legacy = "[{\"a\":\"美女\",\"b\":[{\"a\":\"u1\",\"b\":1125,\"c\":2445,\"d\":false}]}]"
        val groups = EmoticonGroups.decode(legacy)
        assertEquals(1, groups.size)
        assertEquals("美女", groups.single().name)
        assertEquals(CustomEmoticon("u1", 1125, 2445, false), groups.single().emojis.single())
    }

    @Test fun decodeReturnsEmptyForBrokenJson() {
        assertTrue(EmoticonGroups.decode("not json").isEmpty())
        assertTrue(EmoticonGroups.decode("").isEmpty())
    }
}

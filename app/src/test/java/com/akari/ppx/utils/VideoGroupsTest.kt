package com.akari.ppx.utils

import org.junit.Assert.*
import org.junit.Test

class VideoGroupsTest {
    private val video = CustomVideo("v123456", "测试\n视频：猫", "/a/video.mp4", "/a/cover.jpg", 12345, 1080, 1920)

    @Test fun stableJsonRoundTripPreservesMetadataAndEmptyGroups() {
        val groups = listOf(VideoGroup("收藏", mutableListOf(video)), VideoGroup("空分组"))
        val json = VideoGroups.encode(groups)
        assertTrue(json.contains("\"duration\":12345"))
        assertEquals(groups, VideoGroups.decode(json))
    }

    @Test fun movingInBothDirectionsPreservesTheInputAndEmptySource() {
        val groups = listOf(VideoGroup("A", mutableListOf(video)), VideoGroup("B"))
        val moved = VideoGroups.moved(groups, "A", "B", video.id)!!
        assertEquals(listOf(video), groups[0].videos)
        assertTrue(groups[1].videos.isEmpty())
        assertTrue(moved[0].videos.isEmpty())
        assertEquals(listOf(video), moved[1].videos)
        assertEquals(groups, VideoGroups.moved(moved, "B", "A", video.id))
    }

    @Test fun movingDeduplicatesAndKeepsDestinationMetadata() {
        val destination = video.copy(title = "目标标题")
        val moved = VideoGroups.moved(listOf(VideoGroup("A", mutableListOf(video)),
            VideoGroup("B", mutableListOf(destination))), "A", "B", video.id)!!
        assertTrue(moved[0].videos.isEmpty())
        assertEquals(listOf(destination), moved[1].videos)
    }

    @Test fun invalidMovesDoNotCreateGroupsOrLoseVideos() {
        val groups = listOf(VideoGroup("A", mutableListOf(video)), VideoGroup("B"))
        assertNull(VideoGroups.moved(groups, "A", "A", video.id))
        assertNull(VideoGroups.moved(groups, "A", "missing", video.id))
        assertNull(VideoGroups.moved(groups, "A", "B", "missing"))
        assertEquals(listOf(video), groups[0].videos)
    }

    @Test(expected = RuntimeException::class) fun malformedManifestIsNotSilentlyReplacedWithEmptyGroups() {
        VideoGroups.decode("[{\"name\":\"收藏\",\"videos\":[{}]}]")
    }
}

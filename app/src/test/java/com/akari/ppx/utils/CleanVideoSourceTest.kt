package com.akari.ppx.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CleanVideoSourceTest {
    private fun stream(url: String) = "{\"main_url\":\"${Base64.getEncoder().encodeToString(url.toByteArray())}\"}"
    private fun response(entries: String) = "{\"video_info\":{\"data\":{\"video_list\":{$entries}}}}"

    @Test fun choosesHighestQualityPlaybackStream() {
        val json = response("\"video_0\":${stream("https://cdn/low.mp4")},\"video_5\":${stream("https://cdn/high.mp4")}")
        assertEquals("https://cdn/high.mp4", CleanVideoSource.decode(json))
    }

    @Test fun skipsInvalidStreamsWithoutUsingWatermarkedDownloadFields() {
        val json = response("\"video_5\":${stream("file:///bad")},\"video_4\":{\"main_url\":\"!!!\"},\"video_1\":${stream("https://cdn/clean.mp4")}")
        assertEquals("https://cdn/clean.mp4", CleanVideoSource.decode(json))
    }

    @Test(expected = IllegalStateException::class) fun missingPlaybackStreamFailsExplicitly() {
        CleanVideoSource.decode("{\"download_url\":\"https://cdn/watermarked.mp4\"}")
    }

    @Test fun acceptsIdsAndPlaybackPathsButRejectsArbitraryDownloads() {
        assertEquals("v123456", CleanVideoSource.videoId("v123456"))
        assertEquals("v123456", CleanVideoSource.videoId("https://host/video/mp4/v123456?x=1"))
        assertNull(CleanVideoSource.videoId("https://cdn/random.mp4"))
        assertNull(CleanVideoSource.videoId("../../bad"))
    }
}

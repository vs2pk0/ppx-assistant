package com.akari.ppx.xp.hook.purity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoUriTest {
    private fun extract(url: String): String? = VideoHook::class.java
        .getDeclaredMethod("extractVideoUri", String::class.java).run {
            isAccessible = true
            invoke(VideoHook(), url) as? String
        }

    @Test fun cdnPlaybackUrlPreservesOriginalIdentity() {
        assertNull(extract("https://cdn.example/video/tos/cn/asset/?a=0"))
    }

    @Test fun legacyPlayUrlExtractsOnlyTheId() {
        assertEquals("v0304test", extract("https://example/video/play/mp4/v0304test?token=1#part"))
    }

    @Test fun emptyOrNestedPathDoesNotReplaceIdentity() {
        assertNull(extract("https://example/mp4/"))
        assertNull(extract("https://example/mp4/a/b"))
    }
}

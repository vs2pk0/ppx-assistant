package com.akari.ppx.xp.hook.purity

import com.sup.android.mi.feed.repo.bean.ad.AdFeedCell
import com.sup.android.mi.feed.repo.bean.ad.CommentAdModel
import com.sup.android.mi.feed.repo.bean.ad.DerivedAdFeedCell
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdHookTest {
    private val hook = AdHook()

    private fun matches(method: String, value: Any?): Boolean =
        AdHook::class.java.getDeclaredMethod(method, Any::class.java).run {
            isAccessible = true
            invoke(hook, value) as Boolean
        }

    @Test
    fun commentAdsAreRemovedEvenWhenAdInfoIsNull() {
        assertTrue(matches("isCommentAdFeedCell", AdFeedCell()))
        assertTrue(matches("isCommentAdFeedCell", DerivedAdFeedCell()))
        assertTrue(matches("isCommentAdFeedCell", CommentAdModel()))
    }

    @Test
    fun ordinaryContentAndNullArePreserved() {
        for (method in listOf("isCommentAdFeedCell", "isMainFeedAdCell", "isCommentAdDockerData")) {
            assertFalse(matches(method, Any()))
            assertFalse(matches(method, null))
        }
    }

    @Test
    fun wrappedCommentAdsAreRecognized() {
        assertTrue(matches("isCommentAdDockerData", DockerData(CommentAdModel())))
        assertTrue(matches("isCommentAdDockerData", DockerData(AdFeedCell())))
        assertFalse(matches("isCommentAdDockerData", DockerData(Any())))
    }

    @Test
    fun feedAdSubclassesAreRemoved() {
        assertTrue(matches("isMainFeedAdCell", DerivedAdFeedCell()))
    }

    class DockerData(private val data: Any) {
        fun getCellData(): Any = data
    }
}

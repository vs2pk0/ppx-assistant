package com.akari.ppx.utils

import org.junit.Assert.*
import org.junit.Test

class ForwardContentTest {
    class MidVideoPost(val content: String, val videoMid: Image)
    class VideoFeedItem(val content: String)
    class OtherCell(val feedItem: Any)

    @Test fun midQualityVideoIsNotDropped() {
        val result = ForwardContent.from(OtherCell(MidVideoPost("视频", Image(listOf(Url("https://example.com/m.mp4"))))))
        assertTrue(result.media.single().video)
    }

    @Test(expected = IllegalStateException::class) fun unknownVideoMustNotBecomeTextOnly() {
        ForwardContent.from(OtherCell(VideoFeedItem("视频")))
    }
    class Url(val url: String)
    class Image(val urlList: List<Url>)
    class Post(val content: String, val multiImage: List<Image> = emptyList(), val videoHigh: Image? = null)
    class Reply(val text: String?, val images: List<Image> = emptyList())
    class Cell(val feedItem: Post?, val comment: Reply? = null, val reply: Reply? = null)

    @Test fun plainTextIsPreserved() {
        val result = ForwardContent.from(Cell(Post("第一行\n第二行")))
        assertEquals("第一行\n第二行", result.text)
        assertTrue(result.media.isEmpty())
    }

    @Test fun allImagesKeepTheirOrderIncludingDuplicates() {
        val first = Image(listOf(Url("https://example.com/1.jpg")))
        val second = Image(listOf(Url("https://example.com/2.jpg")))
        val result = ForwardContent.from(Cell(Post("两张图", listOf(first, second, first))))
        assertEquals(listOf("1.jpg", "2.jpg", "1.jpg"), result.media.map { it.urls.single().substringAfterLast('/') })
    }

    @Test fun replyDoesNotCopyParentTextOrMedia() {
        val cell = Cell(Post("父帖", videoHigh = Image(listOf(Url("https://example.com/p.mp4")))) ,
            Reply("父评论"), Reply(null, listOf(Image(listOf(Url("https://example.com/r.gif"))))))
        val result = ForwardContent.from(cell)
        assertEquals("", result.text)
        assertNull(ForwardContent.text(cell))
        assertFalse(result.media.single().video)
        assertTrue(result.media.single().urls.single().endsWith("r.gif"))
    }

    @Test fun videoIncludesTextAndDownloadAlternatives() {
        val result = ForwardContent.from(Cell(Post("视频文字", videoHigh = Image(listOf(Url("https://example.com/a.mp4"), Url("https://example.com/b.mp4"))))))
        assertTrue(result.media.single().video)
        assertEquals(2, result.media.single().urls.size)
        assertEquals("视频文字", result.text)
    }

    @Test(expected = IllegalStateException::class) fun missingImageDoesNotSilentlyProducePartialPost() {
        ForwardContent.from(Cell(Post("不能漏图", listOf(Image(emptyList())))))
    }

    @Test(expected = IllegalStateException::class) fun missingVideoDoesNotSilentlyProduceTextOnlyPost() {
        ForwardContent.from(Cell(Post("不能漏视频", videoHigh = Image(emptyList()))))
    }
}

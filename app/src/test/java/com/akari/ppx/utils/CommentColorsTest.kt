package com.akari.ppx.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CommentColorsTest {
    @Test fun plainTextAndEmojiUseHostMarkup() {
        assertEquals("[b type=1 id=@]你好😊[/b]", CommentColors.format("你好😊", "blue"))
        assertEquals("[b type=7 color=#D93025]你好[/b]", CommentColors.format("你好", "red"))
    }
    @Test fun disabledEmptyAndRichContentStayIntact() {
        for (text in listOf("", "  ", "[b type=1 id=123]@名字[/b]", "<TIME>12</TIME>"))
            assertEquals(text, CommentColors.format(text, "red"))
        assertEquals("普通评论", CommentColors.format("普通评论", "none"))
        assertEquals("普通评论", CommentColors.format("普通评论", "unknown"))
    }
}

package com.akari.ppx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentGodTest {
    @Test fun onlyTheConfirmedSentStatusReportsSuccess() {
        assertEquals("送神成功", CommentGod.resultMessage(2))
        assertEquals("这条评论的神评已送满", CommentGod.resultMessage(3))
        for (status in listOf(null, -1, 0, 1, 4)) {
            assertNotEquals("送神成功", CommentGod.resultMessage(status))
        }
    }

    @Test fun anAlreadySentOrFullCommentCannotBeSentAgain() {
        assertEquals("这条评论已送神", CommentGod.blockedReason(true, false, 2, 1, 0))
        assertEquals("这条评论的神评已送满", CommentGod.blockedReason(true, false, 3, 1, 0))
    }

    @Test fun sendsOnlyEligiblePublishedCommentsFromOtherAccounts() {
        assertNull(CommentGod.blockedReason(true, false, 1, 1, 0))
        assertEquals("请先登录", CommentGod.blockedReason(false, false, 1, 1, 0))
        assertEquals("不能给自己的评论送神", CommentGod.blockedReason(true, true, 1, 1, 0))
        assertEquals("当前账号没有送神资格", CommentGod.blockedReason(true, false, 1, 0, 0))
        assertEquals("这条评论暂不可送神", CommentGod.blockedReason(true, null, 1, 1, 0))
        assertEquals("这条评论暂不可送神", CommentGod.blockedReason(true, false, 1, 1, 1))
    }
}

package com.akari.ppx.utils

import org.junit.Assert.*
import org.junit.Test

class FilterPatternsTest {
    @Test fun malformedRuleDoesNotDisableValidRules() {
        val errors = mutableListOf<String>()
        val patterns = FilterPatterns("[|.*PPX-TEST.*|某用户") { errors.add(it) }
        assertEquals(listOf("["), errors)
        assertTrue(patterns.matches("兼容性 PPX-TEST 评论"))
        assertTrue(patterns.matches("某用户"))
        assertFalse(patterns.matches("正常评论"))
    }
    @Test fun emptyRulesAndMissingContentArePreserved() {
        assertFalse(FilterPatterns("").matches(""))
        assertFalse(FilterPatterns(".*").matches(null))
        assertFalse(FilterPatterns("||").matches("正常评论"))
    }
    @Test fun preservesFullMatchSemantics() {
        assertFalse(FilterPatterns("测试").matches("正常测试"))
        assertTrue(FilterPatterns(".*测试.*").matches("正常测试"))
    }
}

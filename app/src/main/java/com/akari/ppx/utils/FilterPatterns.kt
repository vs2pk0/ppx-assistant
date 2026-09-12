package com.akari.ppx.utils

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** A malformed user rule must not prevent the remaining rules from running. */
internal class FilterPatterns(rules: String, onInvalid: (String) -> Unit = {}) {
    private val patterns = rules.split('|').filter { it.isNotEmpty() }.mapNotNull { rule ->
        try {
            Pattern.compile(rule)
        } catch (_: PatternSyntaxException) {
            onInvalid(rule)
            null
        }
    }

    fun matches(text: String?): Boolean = text != null && patterns.any { it.matcher(text).matches() }
}

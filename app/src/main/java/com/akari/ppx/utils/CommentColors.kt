package com.akari.ppx.utils

object CommentColors {
    fun format(text: String, color: String): String {
        val attributes = when (color) {
            "blue" -> "type=1 id=@"
            // 6.2.0 renders legacy type=3 in black; type=7 carries an explicit color.
            "red" -> "type=7 color=#D93025"
            else -> return text
        }
        // Keep mentions/time links and already formatted text intact rather than nesting markup.
        if (text.isBlank() || text.contains("[b ") || text.contains("<TIME>")) return text
        return "[b $attributes]$text[/b]"
    }
}

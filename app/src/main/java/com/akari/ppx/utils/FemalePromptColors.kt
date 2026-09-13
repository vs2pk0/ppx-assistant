package com.akari.ppx.utils

object FemalePromptColors {
    const val DEFAULT = "#FF6880"

    fun normalize(value: String?): String? {
        val hex = value?.trim()?.removePrefix("#") ?: return null
        if (!hex.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return "#${hex.uppercase(java.util.Locale.ROOT)}"
    }

    fun argb(value: String?): Int =
        (0xFF000000L or (normalize(value) ?: DEFAULT).drop(1).toLong(16)).toInt()
}

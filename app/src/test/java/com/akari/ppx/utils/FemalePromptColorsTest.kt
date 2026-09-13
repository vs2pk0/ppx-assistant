package com.akari.ppx.utils

import org.junit.Assert.*
import org.junit.Test

class FemalePromptColorsTest {
    @Test fun existingPinkIsPreserved() {
        assertEquals(-38784, FemalePromptColors.argb(null))
        assertEquals(-38784, FemalePromptColors.argb(FemalePromptColors.DEFAULT))
    }

    @Test fun customColorsAreOpaqueAndNormalized() {
        assertEquals("#1976D2", FemalePromptColors.normalize(" 1976d2 "))
        assertEquals(0xFF1976D2.toInt(), FemalePromptColors.argb("#1976d2"))
        assertEquals(0xFF000000.toInt(), FemalePromptColors.argb("000000"))
    }

    @Test fun malformedOrTransparentColorsFallBackToPink() {
        listOf("", "#123", "#00FFFFFF", "#GGGGGG", "red").forEach {
            assertNull(FemalePromptColors.normalize(it))
            assertEquals(-38784, FemalePromptColors.argb(it))
        }
    }
}

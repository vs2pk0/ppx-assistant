package com.akari.ppx.utils

import org.junit.Assert.*
import org.junit.Test

class AssistantUpdatesTest {
    @Test fun comparesNumericVersions() {
        assertTrue(AssistantUpdates.isNewer("v0.0.10", "0.0.9"))
        assertTrue(AssistantUpdates.isNewer("v0.1.0", "0.0.4"))
        assertFalse(AssistantUpdates.isNewer("v0.0.4", "0.0.4"))
        assertFalse(AssistantUpdates.isNewer("v0.0.3", "0.0.4"))
        assertFalse(AssistantUpdates.isNewer("v0.0.4.0", "0.0.4"))
    }

    @Test fun ignoresNonReleaseVersionTags() {
        assertFalse(AssistantUpdates.isNewer("v0.1.0-beta", "0.0.4"))
        assertFalse(AssistantUpdates.isNewer("latest", "0.0.4"))
        assertFalse(AssistantUpdates.isNewer("", "0.0.4"))
    }
}

package com.akari.ppx.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Protocol shared by the module app (writer) and the hooked host (reader). */
class SettingsSnapshotTest {
    private val lines = listOf(
        "save_image\ttrue",
        "digg_style\t10",
        "username\t",
        "channels\t[{\"name\":\"关注\"}]",
        "empty_value\t"
    )

    @Test fun readsBooleans() {
        assertEquals(true, XPrefs.parseSnapshot(lines, "save_image", false))
    }

    @Test fun readsStrings() {
        assertEquals("10", XPrefs.parseSnapshot(lines, "digg_style", ""))
        assertEquals("", XPrefs.parseSnapshot(lines, "username", ""))
    }

    @Test fun keepsJsonPayloadsIntact() {
        assertEquals(
            "[{\"name\":\"关注\"}]",
            XPrefs.parseSnapshot(lines, "channels", "")
        )
    }

    /** A prefix match must not leak values from a different key. */
    @Test fun doesNotMatchKeyPrefixes() {
        assertNull(XPrefs.parseSnapshot(listOf("save_image_extra\ttrue"), "save_image", false))
    }

    @Test fun returnsNullForMissingKey() {
        assertNull(XPrefs.parseSnapshot(lines, "not_there", false))
        assertNull(XPrefs.parseSnapshot(emptyList(), "save_image", false))
    }

    @Test fun ignoresGarbageBoolean() {
        assertNull(XPrefs.parseSnapshot(listOf("save_image\tmaybe"), "save_image", false))
    }
}

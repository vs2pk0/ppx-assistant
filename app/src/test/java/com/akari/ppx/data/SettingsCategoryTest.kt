package com.akari.ppx.data

import org.junit.Assert.*
import org.junit.Test

class SettingsCategoryTest {
    @Test fun everySettingAppearsExactlyOnce() {
        val original = prefItems.flatten().filterNotNull().filter {
            it !== ItemDivider && AutomationAvailability.isSettingAvailable(it.settingKey())
        }.map { it.settingKey() }
        val grouped = settingsCategories.flatMap { it.items }.map { it.settingKey() }
        assertEquals(original.sorted(), grouped.sorted())
        assertEquals(grouped.size, grouped.distinct().size)
        assertTrue(settingsCategories.all { it.items.isNotEmpty() })
    }

    @Test fun unavailableAutomationIsAbsentFromCategoriesAndSearch() {
        val legacyAutoKeys = prefItems[2].filterNotNull().map { it.settingKey() }.toSet()
        assertEquals(legacyAutoKeys, AutomationAvailability.disabledKeys)
        assertFalse(settingsCategories.any { it.title == "自动操作" })
        assertTrue(settingsCategories.flatMap { it.items }.none { it.settingKey() in legacyAutoKeys })
    }

    @Test fun legacyAutoHooksAreBlockedButManualFeaturesRemainAvailable() {
        listOf("BrowseHook", "CommonHook", "CommentDiggHook", "WardHook", "ShareHook").forEach {
            assertFalse(AutomationAvailability.isHookAvailable("com.akari.ppx.xp.hook.auto.$it"))
        }
        assertTrue(AutomationAvailability.isHookAvailable("com.akari.ppx.xp.hook.assist.SendGodHook"))
        assertTrue(AutomationAvailability.isHookAvailable("com.akari.ppx.xp.hook.purity.ShareHook"))
        assertTrue(AutomationAvailability.isSettingAvailable("unlock_send_god_limit"))
        assertTrue(AutomationAvailability.isSettingAvailable("save_video"))
    }

    @Test fun disabledSwitchesIgnoreEnabledDefaultsWithoutReadingFrameworkPreferences() {
        AutomationAvailability.disabledKeys.forEach { key ->
            assertFalse(key, XPrefs<Boolean>(key, true))
        }
    }

    @Test fun dependentEditorsStayWithTheirSwitches() {
        settingsCategories.forEach { category ->
            category.items.forEach { item ->
                val dependency = when (item) {
                    is EditItem -> item.dependency
                    is ListItem -> item.dependency
                    else -> null
                }
                if (dependency != null) assertTrue("${item.settingKey()} missing $dependency", category.items.any { it.settingKey() == dependency })
            }
        }
    }
}

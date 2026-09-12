package com.akari.ppx.data

import org.junit.Assert.*
import org.junit.Test

class SettingsCategoryTest {
    @Test fun everySettingAppearsExactlyOnce() {
        val original = prefItems.flatten().filterNotNull().filter { it !== ItemDivider }.map { it.settingKey() }
        val grouped = settingsCategories.flatMap { it.items }.map { it.settingKey() }
        assertEquals(original.sorted(), grouped.sorted())
        assertEquals(grouped.size, grouped.distinct().size)
        assertTrue(settingsCategories.all { it.items.isNotEmpty() })
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

package com.akari.ppx.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.akari.ppx.data.*
import com.akari.ppx.data.Const.CHANNEL_DEFAULT
import com.akari.ppx.data.Const.CHANNEL_KEY
import com.akari.ppx.data.model.ChannelItem
import com.akari.ppx.data.model.CheckBoxItem
import com.akari.ppx.ui.widget.*
import com.akari.ppx.utils.fromJsonList
import com.akari.ppx.utils.get
import com.akari.ppx.utils.splitByOr
import com.akari.ppx.utils.toJson

@Composable
fun PreferenceItem(
    preference: Preference.PreferenceItem<*>,
) {
    val prefs by Prefs.dsData.collectAsState(initial = null)
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.surface) {
    when (preference) {
        is Preference.PreferenceItem.TextPreference -> {
            TextPreferenceWidget(
                preference = preference,
                onClick = { preference.onClick(context) }
            )
        }
        is Preference.PreferenceItem.SwitchPreference -> {
            SwitchPreferenceWidget(
                preference = preference,
                value = Prefs.dataStore.get(
                    preference.key,
                    false
                ).value.flow.collectAsState(initial = false).value,
                onValueChange = { newValue ->
                    Prefs.set(preference.key, newValue)
                    preference.onClick(context, newValue)
                }
            )
        }
        is Preference.PreferenceItem.ListPreference -> {
            ListPreferenceWidget(
                preference = preference,
                value = prefs?.get(stringPreferencesKey(preference.key)) ?: run {
                    Prefs.get<String>(preference.key) ?: run {
                        Prefs.set(preference.key, preference.default)
                    }
                    preference.default
                },
                onValueChange = { newValue ->
                    Prefs.set(preference.key, newValue)
                }
            )
        }
        is Preference.PreferenceItem.EditPreference -> {
            EditPreferenceWidget(
                preference = preference,
                value = prefs?.get(stringPreferencesKey(preference.key)) ?: run {
                    Prefs.get<String>(preference.key) ?: run {
                        Prefs.set(preference.key, preference.default)
                    }
                    preference.default
                },
                default = preference.default,
                summary = { v, m -> if (m) "共${v.splitByOr().size}条数据" else "${if (v.isBlank()) "" else "当前："}$v" },
                onValueChange = { newValue ->
                    Prefs.set(preference.key, newValue)
                }
            )
        }
        is Preference.PreferenceItem.CheckboxListPreference -> {
            val raw = prefs?.get(stringPreferencesKey(preference.key)) ?: Prefs.get<String>(preference.key)
            val items = androidx.compose.runtime.remember(raw) {
                raw?.fromJsonList<CheckBoxItem>()?.toMutableStateList() ?: preference.items.toMutableStateList()
            }
            CheckBoxListPreferenceWidget(
                preference = preference,
                items = items,
                onDismiss = {
                    Prefs.set(preference.key, items.toJson())
                }
            )
        }
        is Preference.PreferenceItem.ChannelListPreference -> {
            val raw = prefs?.get(stringPreferencesKey(CHANNEL_KEY)) ?: Prefs.get<String>(CHANNEL_KEY)
            val channelItems = androidx.compose.runtime.remember(raw) {
                raw?.fromJsonList<ChannelItem>()?.toMutableStateList() ?: CHANNEL_DEFAULT.toMutableStateList()
            }
            ChannelListPreferenceWidget(
                preference = preference,
                value = prefs?.get(booleanPreferencesKey(preference.key)) ?: false,
                onValueChange = { newValue ->
                    Prefs.set(preference.key, newValue)
                },
                items = channelItems,
                onDismiss = {
                    Prefs.set(CHANNEL_KEY, channelItems.toJson())
                }
            )
        }
    }
    }
}

@Composable
fun PreferenceScreen(
    index: Int,
    state: LazyListState,
    query: String = ""
) {
    val categories = if (query.isBlank()) listOf(settingsCategories[index]) else settingsCategories
    val prefs by Prefs.dsData.collectAsState(initial = null)
    val showSendGodFloor = prefs?.get(stringPreferencesKey(AutoSendGod.MODE)) == AutoSendGod.SPECIFIED
    val sections = categories.flatMap { category ->
        category.sections.map { section ->
            section.copy(
                title = if (query.isBlank()) section.title else "${category.title} · ${section.title}",
                items = section.items.filter { item ->
                    if (item.settingKey() == AutoSendGod.FLOOR && !showSendGodFloor) return@filter false
                    query.isBlank() || when (item) {
                        is SwitchItem -> "${item.title} ${item.summary}".contains(query, true)
                        is TextItem -> "${item.title} ${item.summary}".contains(query, true)
                        is EditItem -> item.title.contains(query, true)
                        is ListItem -> item.title.contains(query, true)
                        is ColorItem -> item.title.contains(query, true)
                        is CheckBoxListItem -> item.title.contains(query, true)
                        is ChannelListItem -> item.title.contains(query, true)
                        else -> false
                    }
                }
            )
        }
    }.filter { it.items.isNotEmpty() }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text(if (query.isBlank()) settingsCategories[index].title
                else "${sections.sumOf { it.items.size }} 项匹配功能",
                Modifier.padding(vertical = 12.dp), fontWeight = FontWeight.Bold)
        }
        sections.forEach { section ->
            item(key = "section:${section.title}") {
                Text(
                    section.title,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.subtitle2,
                    color = MaterialTheme.colors.primary
                )
            }
            section.items.forEach { item ->

                when (item) {
                    is ColorItem -> item(key = item.settingKey()) {
                        val prefs by Prefs.dsData.collectAsState(initial = null)
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colors.surface) {
                            ColorPreferenceWidget(
                                preference = item,
                                value = prefs?.get(stringPreferencesKey(item.key)) ?: item.default,
                                onValueChange = { Prefs.set(item.key, it) }
                            )
                        }
                    }
                    is TextItem -> item(key = item.settingKey()) {
                        PreferenceItem(
                            Preference.PreferenceItem.TextPreference(
                                title = item.title,
                                summary = item.summary,
                                onClick = item.onClick
                            )
                        )
                    }
                    is SwitchItem -> item(key = item.settingKey()) {
                        PreferenceItem(
                            Preference.PreferenceItem.SwitchPreference(
                                key = item.key,
                                title = item.title,
                                summary = item.summary,
                                dependency = item.dependency,
                                onClick = item.onClick
                            )
                        )
                    }
                    is EditItem -> item(key = item.settingKey()) {
                        PreferenceItem(
                            Preference.PreferenceItem.EditPreference(
                                key = item.key,
                                title = item.title,
                                default = item.default,
                                multi = item.multi,
                                positiveInteger = item.positiveInteger,
                                dependency = item.dependency
                            )
                        )
                    }
                    is ListItem -> item(key = item.settingKey()) {
                        PreferenceItem(
                            Preference.PreferenceItem.ListPreference(
                                key = item.key,
                                title = item.title,
                                default = item.default,
                                entries = item.entries,
                                summary = item.summary,
                                dependency = item.dependency
                            )
                        )
                    }
                    is CheckBoxListItem -> item(key = item.settingKey()) {
                        PreferenceItem(
                            Preference.PreferenceItem.CheckboxListPreference(
                                key = item.key,
                                title = item.title,
                                summary = item.summary,
                                items = item.items,
                                dependency = item.dependency
                            )
                        )
                    }
                    is ChannelListItem -> item(key = item.settingKey()) {
                        PreferenceItem(
                            Preference.PreferenceItem.ChannelListPreference(
                                key = item.key,
                                title = item.title,
                                dialogTitle = item.dialogTitle,
                                summary = item.summary,
                                dependency = item.dependency
                            )
                        )
                    }
                    ItemDivider -> item {
                        Divider()
                    }
                }
            }
        }
    }
}

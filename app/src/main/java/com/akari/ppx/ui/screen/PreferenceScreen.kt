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

val channelItems by lazy {
    Prefs.get<String>(CHANNEL_KEY)?.fromJsonList<ChannelItem>()?.toMutableStateList() ?: run {
        Prefs.set(CHANNEL_KEY, CHANNEL_DEFAULT.toJson())
        CHANNEL_DEFAULT.toMutableStateList()
    }
}

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
            val items by lazy {
                Prefs.get<String>(preference.key)?.fromJsonList<CheckBoxItem>()
                    ?.toMutableStateList() ?: run {
                    Prefs.set(preference.key, preference.items.toJson())
                    preference.items
                }
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
    val visible = (if (query.isBlank()) prefItems[index] else prefItems.take(4).flatten()).filter { item ->
        query.isBlank() || when (item) {
            is SwitchItem -> "${item.title} ${item.summary}".contains(query, true)
            is TextItem -> "${item.title} ${item.summary}".contains(query, true)
            is EditItem -> item.title.contains(query, true)
            is ListItem -> item.title.contains(query, true)
            is CheckBoxListItem -> item.title.contains(query, true)
            is ChannelListItem -> item.title.contains(query, true)
            else -> false
        }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text(if (query.isBlank()) listOf("净化与下载", "发布与浏览", "自动操作", "界面与个性化")[index]
                else "${visible.size} 项匹配功能",
                Modifier.padding(vertical = 12.dp), fontWeight = FontWeight.Bold)
        }
        visible.map { item ->
            val key = when (item) {
                is SwitchItem -> item.key
                is ListItem -> item.key
                is ChannelListItem -> item.key
                else -> null
            }
            val section = when (key) {
                "remove_ads" -> "广告与干扰"
                "remove_comments" -> "内容过滤"
                "modify_channels" -> "频道管理"
                "comment_text_color" -> "发布评论"
                "unlock_danmaku" -> "浏览与互动"
                "prevent_mistouch" -> "播放与显示"
                "auto_comment" -> "评论与插眼"
                "customize" -> "个人资料 · 仅本机显示"
                else -> null
            }
            if (section != null && query.isBlank()) item {
                Text(section, Modifier.padding(top = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.subtitle2, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.primary)
            }

            when (item) {
                is TextItem -> item {
                    PreferenceItem(
                        Preference.PreferenceItem.TextPreference(
                            title = item.title,
                            summary = item.summary,
                            onClick = item.onClick
                        )
                    )
                }
                is SwitchItem -> item {
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
                is EditItem -> item {
                    PreferenceItem(
                        Preference.PreferenceItem.EditPreference(
                            key = item.key,
                            title = item.title,
                            default = item.default,
                            multi = item.multi,
                            dependency = item.dependency
                        )
                    )
                }
                is ListItem -> item {
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
                is CheckBoxListItem -> item {
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
                is ChannelListItem -> item {
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
                null -> {}
            }
        }
    }
}

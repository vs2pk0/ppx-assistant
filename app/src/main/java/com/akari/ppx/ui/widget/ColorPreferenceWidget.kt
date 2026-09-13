package com.akari.ppx.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.akari.ppx.data.ColorItem
import com.akari.ppx.data.Preference
import com.akari.ppx.utils.FemalePromptColors
import kotlin.math.roundToInt

@Composable
fun ColorPreferenceWidget(preference: ColorItem, value: String, onValueChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value) }
    val current = FemalePromptColors.normalize(value) ?: preference.default
    TextPreferenceWidget(
        preference = Preference.PreferenceItem.TextPreference(title = preference.title),
        summary = "$current · 重启皮皮虾生效",
        onClick = { draft = current; open = true },
        trailing = {
            Box(Modifier.size(28.dp).background(Color(FemalePromptColors.argb(current)), CircleShape)
                .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = .25f), CircleShape))
        }
    )
    if (!open) return
    val normalized = FemalePromptColors.normalize(draft)
    val argb = FemalePromptColors.argb(draft)
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(preference.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("母虾昵称", color = Color(argb), style = MaterialTheme.typography.h6,
                    modifier = Modifier.padding(vertical = 12.dp))
                val presets = listOf(preference.default, "#E53935", "#F57C00", "#388E3C", "#1976D2", "#7B1FA2", "#00838F", "#455A64")
                presets.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { hex ->
                            Box(Modifier.size(48.dp).semantics { contentDescription = "颜色 $hex" }
                                .selectable(selected = normalized == hex, onClick = { draft = hex })
                                .padding(7.dp).background(Color(FemalePromptColors.argb(hex)), CircleShape)
                                .border(if (normalized == hex) 3.dp else 1.dp,
                                    if (normalized == hex) MaterialTheme.colors.onSurface else Color.LightGray, CircleShape))
                        }
                    }
                }
                OutlinedTextField(value = draft, onValueChange = { draft = it },
                    label = { Text("颜色值 #RRGGBB") }, singleLine = true,
                    isError = normalized == null, modifier = Modifier.fillMaxWidth())
                if (normalized == null) Text("请输入 6 位十六进制颜色", color = MaterialTheme.colors.error)
                listOf("红" to 16, "绿" to 8, "蓝" to 0).forEach { (label, shift) ->
                    val component = (argb ushr shift) and 255
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$label $component", Modifier.width(58.dp))
                        Slider(value = component.toFloat(), valueRange = 0f..255f,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "$label 色分量" },
                            onValueChange = { next ->
                                val rgb = ((argb and (255 shl shift).inv()) or (next.roundToInt() shl shift)) and 0xFFFFFF
                                draft = String.format(java.util.Locale.ROOT, "#%06X", rgb)
                            })
                    }
                }
                TextButton(onClick = { draft = preference.default }) { Text("恢复默认粉色") }
            }
        },
        confirmButton = { TextButton(enabled = normalized != null, onClick = {
            normalized?.let(onValueChange)
            open = false
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } }
    )
}

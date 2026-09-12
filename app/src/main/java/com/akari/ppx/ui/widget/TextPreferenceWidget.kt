package com.akari.ppx.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.akari.ppx.data.Preference

@Composable
fun TextPreferenceWidget(
    preference: Preference.PreferenceItem<*>,
    summary: String? = null,
    onClick: () -> Unit = { },
    trailing: @Composable (() -> Unit)? = null
) {
    val enabled = preference.enabled
    CompositionLocalProvider(LocalContentAlpha provides if (enabled) ContentAlpha.high else ContentAlpha.disabled) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            preference.icon?.let { it(); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(preference.title, style = MaterialTheme.typography.body1,
                    maxLines = if (preference.singleLineTitle) 1 else Int.MAX_VALUE)
                (summary ?: preference.summary)?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = if (enabled) .6f else .38f))
                }
            }
            trailing?.let { Spacer(Modifier.width(12.dp)); it() }
        }
    }
}

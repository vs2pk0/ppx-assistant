package com.akari.ppx.ui

import android.widget.Toast
import android.widget.TextView
import android.text.method.LinkMovementMethod
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import com.akari.ppx.BuildConfig
import com.akari.ppx.utils.AssistantRelease
import com.akari.ppx.utils.AssistantUpdates
import com.akari.ppx.utils.openBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AssistantUpdateState {
    var checking by mutableStateOf(false)
        private set
    var release by mutableStateOf<AssistantRelease?>(null)

    suspend fun check(manual: Boolean, notify: (String) -> Unit) {
        if (checking) return
        checking = true
        try {
            val latest = withContext(Dispatchers.IO) { AssistantUpdates.latest() }
            if (latest != null && AssistantUpdates.isNewer(latest.version, BuildConfig.VERSION_NAME)) {
                release = latest
            } else if (manual) notify("暂无更新")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (manual) notify("检查更新失败，请稍后重试")
        } finally {
            checking = false
        }
    }
}

@Composable
fun rememberAssistantUpdateCheck(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val state = remember { AssistantUpdateState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    LaunchedEffect(Unit) { state.check(false, notify) }
    state.release?.let { release ->
        AlertDialog(
            onDismissRequest = { state.release = null },
            title = { Text("发现新版本 ${release.version}") },
            text = {
                ReleaseMarkdown(release.notes)
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { context.openBrowser(release.url) }
                        .onFailure { notify("无法打开浏览器，请前往 GitHub 版本下载页") }
                }) { Text("前往更新") }
            },
            dismissButton = { TextButton(onClick = { state.release = null }) { Text("暂不更新") } }
        )
    }
    return state.checking to { scope.launch { state.check(true, notify) }; Unit }
}

@Composable
private fun ReleaseMarkdown(notes: String) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val rendered = remember(markwon, notes) { markwon.toMarkdown(notes) }
    val textColor = MaterialTheme.colors.onSurface.toArgb()
    val linkColor = MaterialTheme.colors.primary.toArgb()
    AndroidView(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        factory = { TextView(it).apply {
            textSize = 14f
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
        } },
        update = { view ->
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            markwon.setParsedMarkdown(view, rendered)
        }
    )
}

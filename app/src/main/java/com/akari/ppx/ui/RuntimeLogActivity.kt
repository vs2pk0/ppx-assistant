package com.akari.ppx.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.akari.ppx.BuildConfig
import com.akari.ppx.data.Const
import com.akari.ppx.ui.theme.BaseTheme
import com.akari.ppx.utils.LogStore
import com.akari.ppx.utils.RuntimeLog
import com.akari.ppx.utils.SharedBridge
import com.akari.ppx.utils.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings-screen entry point for the in-app runtime log. */
fun openRuntimeLog(context: Context) {
    context.startActivity(
        Intent(context, RuntimeLogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/**
 * Shows the merged runtime log (module process + forwarded host lines) with
 * one-tap copy/share/clear. Users can attach the exported file to a bug report
 * instead of asking them to set up adb.
 */
class RuntimeLogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BaseTheme { RuntimeLogScreen(onClose = { finish() }) }
        }
    }
}

@Composable
fun RuntimeLogScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    // SAF is the recommended channel: one-time folder grant, no dangerous permission.
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            SharedBridge.saveTreeUri(context, uri)
            context.showToast("已授权日志目录")
        }
    }
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var hostReadable by remember { mutableStateOf(false) }
    var hostPath by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    suspend fun refresh() {
        lines = withContext(Dispatchers.IO) {
            val own = LogStore.read(context, 1500)
            // The hooked host process can only write its own Android/media
            // folder. Reading it needs "所有文件访问" for the module UI.
            val host = SharedBridge.readHostLog(context, 1500)
            hostReadable = SharedBridge.readable(context)
            hostPath = SharedBridge.root(context)?.absolutePath.orEmpty()
            RuntimeLog.mergeLines(own, host, 2000)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(2000)
        }
    }
    LaunchedEffect(lines.size, autoScroll) {
        if (autoScroll && lines.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(lines.lastIndex) }
        }
    }

    val shown = remember(lines, filter) {
        if (filter.isBlank()) lines else lines.filter { it.contains(filter, true) }
    }
    val hostLines = remember(lines) { lines.count { "/host(" in it } }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colors.surface)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("运行日志", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("关闭") }
                }
                Text(
                    "共 ${lines.size} 行 · 宿主进程 $hostLines 行 · 版本 ${BuildConfig.VERSION_NAME}" +
                            "(${BuildConfig.VERSION_CODE})",
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = .6f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Text(
                    if (hostReadable) "宿主日志目录：$hostPath"
                    else "需要「所有文件访问」才能读取宿主日志（点击下方授权）",
                    fontSize = 11.sp,
                    color = if (hostReadable) MaterialTheme.colors.onSurface.copy(alpha = .5f)
                    else Color(0xFFEF6C00),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    placeholder = { Text("过滤关键词，例如 XPrefs / hook / error") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        scope.launch {
                            val text = shown.joinToString("\n")
                            if (text.isBlank()) return@launch
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("皮友助手日志", text))
                            context.showToast("已复制 ${shown.size} 行日志")
                        }
                    }) { Text("复制") }
                    TextButton(onClick = {
                        scope.launch {
                            shareLog(context, shown)
                            context.showToast("正在分享日志")
                        }
                    }) { Text("分享") }
                    if (!hostReadable) {
                        TextButton(onClick = {
                            pickFolder.launch(
                                android.net.Uri.parse(
                                    "content://com.android.externalstorage.documents/document/primary%3A" +
                                            java.net.URLEncoder.encode(
                                                "Android/media/${Const.TARGET_APP_ID}/${Const.APP_NAME}",
                                                "UTF-8"
                                            )
                                )
                            )
                        }) {
                            Text("授权目录", color = Color(0xFFEF6C00))
                        }
                    }
                    TextButton(onClick = {
                        autoScroll = !autoScroll
                    }) { Text(if (autoScroll) "自动滚动：开" else "自动滚动：关") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LogStore.clear(context)
                                SharedBridge.clearHostLog(context)
                            }
                            RuntimeLog.clear()
                            refresh()
                            context.showToast("日志已清空")
                        }
                    }) { Text("清空", color = Color(0xFFED6559)) }
                }
            }
        }
    ) { padding ->
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("暂无日志。打开一次皮皮虾后回来查看。", color = MaterialTheme.colors.onSurface.copy(alpha = .6f))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                state = listState,
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(shown) { _, line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = lineColor(line),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

private fun lineColor(line: String): Color = when {
    " E/" in line -> Color(0xFFD32F2F)
    " W/" in line -> Color(0xFFEF6C00)
    " D/" in line -> Color(0xFF757575)
    else -> Color(0xFF212121)
}

/** Writes the visible lines to a temp file and opens the system share sheet. */
private fun shareLog(context: Context, lines: List<String>) {
    runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(context.cacheDir, "皮友助手日志-$stamp.txt")
        file.writeText(lines.joinToString("\n"))
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "皮友助手运行日志")
                    putExtra(Intent.EXTRA_TEXT, "皮友助手 ${BuildConfig.VERSION_NAME} 运行日志（宿主 ${Const.TARGET_APP_ID}）")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "分享日志"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Opens the system "all files access" screen for this app. */
private fun requestAllFilesAccess(context: Context) {
    val intents = listOf(
        Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ),
        Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    )
    intents.forEach { intent ->
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }.onFailure { }
    }
    context.showToast("请在系统设置中允许「所有文件访问」")
}

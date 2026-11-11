package com.akari.ppx.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.akari.ppx.data.Prefs
import com.akari.ppx.utils.BackupFiles
import com.akari.ppx.utils.EmoticonGroups
import com.akari.ppx.utils.StoragePaths
import com.akari.ppx.utils.Log
import com.akari.ppx.utils.showToast

/** Set by the settings screen so a plain TextItem can open the file picker. */
val importBackupRequest = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

/** Opens the system file picker and imports a complete ZIP or legacy JSON. */
@Composable
fun rememberBackupImporter(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runBackup(context, "导入备份", { progress -> BackupFiles.importUri(context, uri, progress) }) {
            context.showToast(it)
        }
    }
    val open = { if (ensureBackupAccess(context)) launcher.launch(arrayOf("application/zip", "application/json", "text/plain", "*/*")) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        importBackupRequest.set(open)
        onDispose { importBackupRequest.set(null) }
    }
    return open
}

fun showStorageDirDialog(context: Context) {
    val density = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * density + 0.5f).toInt()
    val input = android.widget.EditText(context).apply {
        setText(Prefs.get(StoragePaths.KEY, "").orEmpty())
        hint = StoragePaths.DEFAULT_ROOT
        setSingleLine(false)
        setTextColor(0xFF222222.toInt())
        setHintTextColor(0xFF999999.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat(); setColor(0xFFF5F5F5.toInt())
        }
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    android.app.AlertDialog.Builder(context)
        .setTitle("自定义数据目录")
        .setMessage("留空使用默认目录：\n${StoragePaths.DEFAULT_ROOT}\n\n修改后重启皮皮虾生效。")
        .setView(input)
        .setPositiveButton("保存") { _, _ ->
            setStorageDir(context, input.text.toString())
        }
        .setNeutralButton("导出当前备份") { _, _ -> exportBackup(context) }
        .setNegativeButton("取消", null)
        .show()
}

fun exportBackup(context: Context) {
    if (!ensureBackupAccess(context)) return
    runBackup(context, "导出完整备份", { progress -> BackupFiles.export(context, progress) }) { file ->
        context.showToast("设置、表情和视频已导出到 ${file.absolutePath}")
        shareFile(context, file)
    }
}

private val backupRunning = java.util.concurrent.atomic.AtomicBoolean(false)

private fun ensureBackupAccess(context: Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()) return true
    android.app.AlertDialog.Builder(context)
        .setTitle("备份需要访问媒体文件")
        .setMessage("请为皮友助手开启所有文件访问权限，以读取和恢复皮皮虾目录中的表情、视频及封面。开启后返回重试。")
        .setPositiveButton("去开启") { _, _ ->
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")))
        }.setNegativeButton("取消", null).show()
    return false
}

/** Media can be large; never zip, download or restore on the main thread. */
private fun <T> runBackup(context: Context, title: String, work: ((String) -> Unit) -> T, success: (T) -> Unit) {
    if (!backupRunning.compareAndSet(false, true)) { context.showToast("正在处理备份，请稍候"); return }
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val dialog = android.app.ProgressDialog(context, android.app.AlertDialog.THEME_DEVICE_DEFAULT_LIGHT).apply {
        setTitle(title); setMessage("正在准备…"); setCancelable(false); show()
    }
    Thread({
        val result = runCatching { work { message -> main.post {
            if (dialog.isShowing) dialog.setMessage(message)
        } } }
        main.post {
            backupRunning.set(false)
            runCatching { dialog.dismiss() }
            val activity = context as? android.app.Activity
            if (activity?.isDestroyed != true && activity?.isFinishing != true) {
                result.onSuccess(success).onFailure {
                    Log.e(it)
                    android.app.AlertDialog.Builder(context).setTitle("${title}失败")
                        .setMessage(it.message ?: "文件格式错误或媒体文件缺失，请检查备份后重试")
                        .setPositiveButton("知道了", null).show()
                }
            }
        }
    }, "ppx-backup").start()
}

fun shareFile(context: Context, file: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = if (file.extension == "zip") "application/zip" else "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "分享备份"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure(Log::e)
}

fun setStorageDir(context: Context, raw: String) {
    Prefs.setBlocking(StoragePaths.KEY, StoragePaths.normalize(raw))
    context.showToast("已保存目录，重启皮皮虾后生效")
}

fun storageSummary(): String = StoragePaths.root().absolutePath

fun emoticonStoreSummary(): String = EmoticonGroups.storePath()

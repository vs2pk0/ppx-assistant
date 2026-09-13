package com.akari.ppx.ui.screen

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.akari.ppx.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun openExternal(context: Context, url: String, packageName: String, fallback: String? = null) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(packageName))
    } catch (_: Exception) {
        if (fallback != null) try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
        } catch (_: Exception) {
            Toast.makeText(context, "未找到可打开链接的应用", Toast.LENGTH_LONG).show()
        } else Toast.makeText(context, "未安装对应应用，请使用收款码", Toast.LENGTH_LONG).show()
    }
}

// drawable-nodpi 中的 PNG/JPEG 可直接读取原始字节，避免重新编码收款码。
@android.annotation.SuppressLint("ResourceType")
@Composable
fun DonationDialog(wechat: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    val label = if (wechat) "微信" else "支付宝"
    val resource = if (wechat) R.drawable.donate_wechat else R.drawable.donate_alipay
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
      Surface(shape = MaterialTheme.shapes.large) {
       Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("$label 赞赏", style = MaterialTheme.typography.h6)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Image(painterResource(resource), "$label 收款码", Modifier.fillMaxWidth().height(240.dp))
            Text(if (wechat) "保存收款码后，打开微信扫一扫，从相册选择图片，再输入赞赏金额。"
                else "打开支付宝后输入赞赏金额；若无法直达，请保存收款码并使用支付宝扫一扫。")
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        check(Build.VERSION.SDK_INT >= 29) { "当前系统请使用另一台设备扫码" }
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, "皮友助手-${label}赞赏-${System.currentTimeMillis()}." + if (wechat) "png" else "jpg")
                            put(MediaStore.Images.Media.MIME_TYPE, if (wechat) "image/png" else "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/皮友助手")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                        try {
                            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                                context.resources.openRawResource(resource).use { it.copyTo(output) }
                            }
                            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
                    } }
                    saving = false
                    Toast.makeText(context, if (result.isSuccess) "收款码已保存到相册" else "保存失败：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }) { Text(if (saving) "正在保存…" else "保存收款码到相册") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss) { Text("关闭") }
        TextButton(onClick = {
            if (wechat) {
                try {
                    val intent = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")
                    if (intent != null) context.startActivity(intent)
                    else Toast.makeText(context, "未安装微信，请使用收款码", Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    Toast.makeText(context, "无法打开微信，请手动打开扫一扫", Toast.LENGTH_LONG).show()
                }
            }
            else openExternal(context, "alipays://platformapi/startapp?appId=20000067&url=" + Uri.encode("https://qr.alipay.com/fkx16942xp8twwtstnhnd33"), "com.eg.android.AlipayGphone")
        }) { Text("打开$label") }
        }
       }
      }
    }
}

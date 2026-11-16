package com.akari.ppx.ui.screen

import android.content.ActivityNotFoundException
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.akari.ppx.BuildConfig.VERSION_NAME
import com.akari.ppx.R
import com.akari.ppx.data.Const.GIT_PAGE_URI
import com.akari.ppx.utils.VersionChecker
import com.akari.ppx.utils.openBrowser
import com.akari.ppx.utils.showToast

@Composable
fun AboutScreen(isActive: Boolean, checkingUpdate: Boolean, onCheckUpdate: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colors
    val secondaryText = colors.onSurface.copy(alpha = 0.72f)
    val cardShape = RoundedCornerShape(20.dp)
    var donation by rememberSaveable { mutableStateOf<Boolean?>(null) }
    donation?.let { DonationDialog(it) { donation = null } }

    fun openLink(url: String) {
        try {
            context.openBrowser(url)
        } catch (_: ActivityNotFoundException) {
            context.showToast("未找到可打开链接的应用")
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.icon), null, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("皮友助手", style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold)
                Text(
                    "版本 $VERSION_NAME · " + if (isActive) "框架已启用" else "框架未启用",
                    style = MaterialTheme.typography.body2,
                    color = secondaryText
                )
            }
        }

        Card(shape = cardShape, backgroundColor = colors.primary.copy(alpha = 0.10f), elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("赞赏支持", style = MaterialTheme.typography.h6, fontWeight = FontWeight.Bold, color = colors.primary)
                Text("如果皮友助手用得顺手，欢迎支持后续维护和版本适配。", style = MaterialTheme.typography.body2)
                Button(
                    onClick = { openLink("https://afdian.com/a/mount-sb996") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = ButtonDefaults.elevation(defaultElevation = 0.dp)
                ) { Text("爱发电支持", Modifier.padding(vertical = 4.dp)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { donation = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text("微信赞赏", Modifier.padding(vertical = 4.dp))
                    }
                    OutlinedButton(onClick = { donation = false }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) {
                        Text("支付宝赞赏", Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }

        val coolapkUrl = "https://www.coolapk.com/u/270261"
        Card(shape = cardShape, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth()) {
                AboutLinkRow("更多软件", "mount.sb996.cn") { openLink("https://mount.sb996.cn/") }
                Divider(Modifier.padding(horizontal = 16.dp))
                AboutLinkRow("酷安", "我的馬眼会发光") {
                    openExternal(context, coolapkUrl, "com.coolapk.market", coolapkUrl)
                }
            }
        }

        Card(shape = cardShape, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("版本适配", fontWeight = FontWeight.Bold)
                Text("已适配皮皮虾 ${VersionChecker.supportedVersionLabel}", style = MaterialTheme.typography.body2)
                Text("当前安装 ${VersionChecker.targetVersion}", style = MaterialTheme.typography.caption, color = secondaryText)
                TextButton(onClick = onCheckUpdate, enabled = !checkingUpdate) {
                    Text(if (checkingUpdate) "正在检查更新…" else "检查更新")
                }
            }
        }

        val projectLinks = listOf(
            "项目源码" to GIT_PAGE_URI,
            "问题反馈" to "$GIT_PAGE_URI/issues",
            "版本下载" to "$GIT_PAGE_URI/releases",
            "GitHub 主页" to "https://github.com/vs2pk0"
        )
        Card(shape = cardShape, elevation = 0.dp) {
            Column(Modifier.fillMaxWidth()) {
                projectLinks.forEachIndexed { index, (title, url) ->
                    if (index > 0) Divider(Modifier.padding(horizontal = 16.dp))
                    AboutLinkRow(title) { openLink(url) }
                }
            }
        }
        Text(
            "由我的馬眼会发光（vs2pk0）维护，基于 Secack / Akari 的开源项目继续开发。\n原项目 © 2020–2022 Secack，遵循 GPL-3.0 许可证。",
            style = MaterialTheme.typography.caption,
            color = secondaryText,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun AboutLinkRow(title: String, description: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            description?.let {
                Text(it, style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f))
            }
        }
        Text("›", style = MaterialTheme.typography.h6, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f))
    }
}

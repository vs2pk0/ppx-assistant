package com.akari.ppx.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akari.ppx.BuildConfig.VERSION_NAME
import com.akari.ppx.R
import com.akari.ppx.data.Const.GIT_PAGE_URI
import com.akari.ppx.ui.widget.AboutCardWidget
import com.akari.ppx.utils.openBrowser

@Composable
fun AboutScreen(isActive: Boolean) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Image(painterResource(R.drawable.icon), null, Modifier.size(80.dp))
        Text("皮皮虾助手", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("lilei · @vs2pk0", style = MaterialTheme.typography.subtitle1)
        Text("版本 $VERSION_NAME · " + if (isActive) "框架已启用" else "框架未启用",
            style = MaterialTheme.typography.caption)
        listOf(
            "GitHub 主页" to "https://github.com/vs2pk0",
            "项目源码" to GIT_PAGE_URI,
            "问题反馈" to "$GIT_PAGE_URI/issues",
            "版本下载" to "$GIT_PAGE_URI/releases"
        ).forEach { (label, url) ->
            AboutCardWidget(Modifier.height(64.dp), onClick = { context.openBrowser(url) }) {
                Text(label, fontWeight = FontWeight.Medium)
            }
        }
        Text("当前维护：vs2pk0\n基于 Secack / Akari 的开源项目继续开发。\n原项目 Copyright © 2020–2022 Secack，遵循 GPL-3.0 许可证。",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f))
    }
}

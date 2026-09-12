package com.akari.ppx.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akari.ppx.ui.screen.PreferenceScreen
import com.akari.ppx.ui.theme.BaseTheme
import kotlinx.coroutines.launch
import com.akari.ppx.BuildConfig.APPLICATION_ID
import com.akari.ppx.R
import com.akari.ppx.data.FrameworkScopeState
import com.akari.ppx.data.HookStatusImpl
import com.akari.ppx.data.prefTabs
import com.akari.ppx.ui.screen.AboutScreen
import com.akari.ppx.utils.VersionChecker.targetVersion
import com.akari.ppx.utils.startPPX

class MainActivity : ComponentActivity() {
    private val isActiveState = mutableStateOf(false)
    private val scopeListener = object : FrameworkScopeState.Listener {
        override fun onStateChanged(active: Boolean?) {
            runOnUiThread {
                isActiveState.value = active ?: isLegacyHookActive()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActiveState.value = isModuleActive()
        setContent {
            BaseTheme {
                val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                val pager = rememberPagerState(pageCount = { prefTabs.size })
                val coroutineScope = rememberCoroutineScope()
                var query by androidx.compose.runtime.remember { mutableStateOf("") }
                Scaffold(
                    modifier = Modifier.background(MaterialTheme.colors.background).safeDrawingPadding(),
                    backgroundColor = MaterialTheme.colors.background,
                    topBar = {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(painterResource(R.drawable.icon), null,
                                    Modifier.size(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text("皮皮虾助手", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                                    Text("让每一次刷虾更顺手", style = MaterialTheme.typography.caption,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = .6f))
                                }
                                TextButton(onClick = { startPPX(this@MainActivity) }) { Text("打开皮皮虾") }
                            }
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                                placeholder = { Text("搜索功能，例如：去水印、评论") }, singleLine = true,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        }
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        TabRow(selectedTabIndex = pager.currentPage,
                            backgroundColor = MaterialTheme.colors.background,
                            contentColor = MaterialTheme.colors.primary, divider = {}) {
                            prefTabs.forEachIndexed { index, title ->
                                Tab(selected = pager.currentPage == index,
                                    onClick = { coroutineScope.launch { pager.animateScrollToPage(index) } },
                                    text = { Text(title, fontWeight = FontWeight.SemiBold) })
                            }
                        }
                        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                            Column(Modifier.fillMaxSize()) {
                                if (index == 0 && query.isBlank()) {
                                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                                        backgroundColor = MaterialTheme.colors.primary.copy(alpha = .08f), elevation = 0.dp) {
                                        Column(Modifier.padding(18.dp)) {
                                            Text(if (isActiveState.value) "框架已启用助手" else "等待框架启用",
                                                fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary)
                                            Spacer(Modifier.height(5.dp))
                                            Text("助手 ${com.akari.ppx.BuildConfig.VERSION_NAME}  ·  皮皮虾 $targetVersion",
                                                style = MaterialTheme.typography.body2)
                                            Text("修改后重启皮皮虾生效；启动提示可确认本次加载。",
                                                style = MaterialTheme.typography.caption,
                                                color = MaterialTheme.colors.onSurface.copy(alpha = .6f))
                                        }
                                    }
                                }
                                if (index == 4 && query.isBlank()) AboutScreen(isActiveState.value)
                                else PreferenceScreen(index, rememberLazyListState(), query)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActiveState.value = isModuleActive()
    }

    override fun onStart() {
        super.onStart()
        FrameworkScopeState.addListener(scopeListener)
    }

    override fun onStop() {
        FrameworkScopeState.removeListener(scopeListener)
        super.onStop()
    }

    companion object {
        fun isModuleActive(): Boolean {
            FrameworkScopeState.isTargetAppScoped()?.let { return it }
            return isLegacyHookActive()
        }

        private fun isLegacyHookActive(): Boolean = HookStatusImpl.sLegacyHookMode

        operator fun invoke(context: Context) = Intent().also {
            ComponentName(APPLICATION_ID, this::class.java.name.split("$")[0]).let(it::setComponent)
        }.let { context.startActivity(it) }
    }
}

package com.clipdown.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clipdown.app.ClipDownApp
import com.clipdown.app.data.CookieStore
import com.clipdown.app.floatwindow.FloatingWindowService
import com.clipdown.app.update.UpdateSection
import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.model.Platform
import kotlinx.coroutines.launch

/**
 * 设置页：监听范围、交互方式、下载策略与解析增强（Cookie / 远端服务）。
 *
 * 所有改动即时生效：解析内核与下载引擎都支持配置热更新。
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = ClipDownApp.get().settings
    val scope = rememberCoroutineScope()

    val floatEnabled by settings.floatEnabled.collectAsStateWithLifecycle(initialValue = true)
    val autoPopup by settings.autoPopup.collectAsStateWithLifecycle(initialValue = true)
    val autoDownload by settings.autoDownload.collectAsStateWithLifecycle(initialValue = true)
    val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle(initialValue = false)
    val saveAlbum by settings.saveToAlbum.collectAsStateWithLifecycle(initialValue = true)
    val maxConcurrent by settings.maxConcurrent.collectAsStateWithLifecycle(initialValue = 3)
    val remoteEnabled by settings.remoteEnabled.collectAsStateWithLifecycle(initialValue = false)
    val remoteEndpoint by settings.remoteEndpoint.collectAsStateWithLifecycle(initialValue = "")
    val remoteToken by settings.remoteToken.collectAsStateWithLifecycle(initialValue = "")
    val enabledPlatforms by settings.enabledPlatforms.collectAsStateWithLifecycle(initialValue = emptySet())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Section("悬浮窗") {
                SwitchRow("启用悬浮球", "常驻屏幕边缘，点击可立即识别剪贴板", floatEnabled) { v ->
                    scope.launch {
                        settings.setFloatEnabled(v)
                        if (v) FloatingWindowService.tryStart(context) else FloatingWindowService.stop(context)
                    }
                }
                SwitchRow("自动弹出解析窗", "识别到支持的链接后自动弹窗（关闭后只在气泡上做角标提示）", autoPopup) { v ->
                    scope.launch { settings.setAutoPopup(v) }
                }
                SwitchRow(
                    "解析后自动下载",
                    "识别到单个视频/图片直接下载（气泡显示进度），图集等会弹窗让你挑选；" +
                        "自动下载连续失败会自动熔断退避，配合「仅 Wi-Fi 下载」可在移动网络下暂停",
                    autoDownload
                ) { v ->
                    scope.launch { settings.setAutoDownload(v) }
                }
            }
        }

        item {
            Section("下载") {
                SwitchRow("仅 Wi-Fi 下载", "移动网络下自动挂起任务", wifiOnly) { v ->
                    scope.launch { settings.setWifiOnly(v) }
                }
                SwitchRow("保存到系统相册", "关闭后仅保存在应用私有目录", saveAlbum) { v ->
                    scope.launch { settings.setSaveToAlbum(v) }
                }
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text("最大并发任务数：${maxConcurrent}", style = MaterialTheme.typography.bodyMedium)
                    var sliderValue by remember(maxConcurrent) { mutableFloatStateOf(maxConcurrent.toFloat()) }
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = {
                            scope.launch { settings.setMaxConcurrent(sliderValue.toInt()) }
                        },
                        valueRange = 1f..6f,
                        steps = 4
                    )
                }
            }
        }

        item {
            Section("监听范围") {
                Text(
                    "未勾选任何平台时表示全部监听",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(6.dp))
                Platform.entries.filter { it != Platform.GENERIC }.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = if (enabledPlatforms.contains(p.id) && enabledPlatforms.isNotEmpty()) {
                                    enabledPlatforms - p.id
                                } else {
                                    enabledPlatforms + p.id
                                }
                                scope.launch {
                                    settings.setEnabledPlatforms(next)
                                    ParserEngine.updateConfig(settings.parserConfig())
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = enabledPlatforms.isEmpty() || enabledPlatforms.contains(p.id),
                            onCheckedChange = null
                        )
                        Text(p.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            Section("登录态增强") {
                Text(
                    "Instagram / 小红书 / Facebook 在免登录状态下只能拿到低清或封面。" +
                        "从浏览器复制对应站点的 Cookie 粘贴到这里，即可获取原画质。Cookie 只保存在本机。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(10.dp))
                CookieStore.platformsNeedingCookie().forEach { p ->
                    var text by remember(p) { mutableStateOf(CookieStore.get(context, p) ?: "") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            CookieStore.put(context, p, it)
                        },
                        label = { Text("${p.displayName} Cookie") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        item {
            Section("远端解析兜底") {
                Text(
                    "本地解析会因平台风控随时失效。可自建 cobalt 或 yt-dlp 服务端，本地失败时自动降级调用。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(10.dp))
                SwitchRow("启用远端解析", "仅在本机解析失败时使用", remoteEnabled) { v ->
                    scope.launch {
                        settings.setRemoteEnabled(v)
                        ParserEngine.updateConfig(settings.parserConfig())
                    }
                }
                var endpoint by remember(remoteEndpoint) { mutableStateOf(remoteEndpoint) }
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it
                        scope.launch {
                            settings.setRemoteEndpoint(it)
                            ParserEngine.updateConfig(settings.parserConfig())
                        }
                    },
                    label = { Text("服务地址") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                var token by remember(remoteToken) { mutableStateOf(remoteToken) }
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it
                        scope.launch {
                            settings.setRemoteToken(it)
                            ParserEngine.updateConfig(settings.parserConfig())
                        }
                    },
                    label = { Text("鉴权 Key（可留空）") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        item { UpdateSection() }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

package com.clipdown.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
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
import com.clipdown.app.ui.components.AppCard
import com.clipdown.app.ui.components.CardHeader
import com.clipdown.app.ui.components.Hairline
import com.clipdown.app.ui.components.PageHeader
import com.clipdown.app.ui.components.SettingSwitchRow
import com.clipdown.app.ui.components.StatusPill
import com.clipdown.app.ui.components.Tone
import com.clipdown.app.ui.components.VSpace
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.app.update.UpdateSection
import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.model.Platform
import kotlinx.coroutines.launch

/**
 * 设置页：监听范围、交互方式、下载策略、登录态与解析增强。
 *
 * 版式统一为「卡片 = 标题 + 说明 + 若干等距行」：
 * 行高、行间距、标题字号全部走共享组件，避免历史上各区块各写一套 padding。
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
        contentPadding = PaddingValues(AppTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
    ) {
        item {
            PageHeader(
                title = "设置",
                subtitle = "所有改动即时生效，解析内核与下载引擎都支持热更新"
            )
        }

        item {
            AppCard {
                CardHeader(title = "悬浮窗", subtitle = "在其他应用上方的常驻入口")
                VSpace(AppTheme.spacing.xs)
                SettingSwitchRow(
                    title = "启用悬浮球",
                    desc = "常驻屏幕边缘，点击可立即识别剪贴板",
                    checked = floatEnabled
                ) { v ->
                    scope.launch {
                        settings.setFloatEnabled(v)
                        if (v) FloatingWindowService.tryStart(context) else FloatingWindowService.stop(context)
                    }
                }
                Hairline()
                SettingSwitchRow(
                    title = "自动弹出解析窗",
                    desc = "识别到链接后自动弹窗；关闭则只在气泡上做角标提示",
                    checked = autoPopup
                ) { v -> scope.launch { settings.setAutoPopup(v) } }
                Hairline()
                SettingSwitchRow(
                    title = "解析后自动下载",
                    desc = "单个视频/图片直接下载并显示进度；图集会弹窗让你挑选。" +
                        "连续失败会自动熔断退避，可配合「仅 Wi-Fi 下载」在移动网络下暂停",
                    checked = autoDownload
                ) { v -> scope.launch { settings.setAutoDownload(v) } }
            }
        }

        item {
            AppCard {
                CardHeader(title = "下载", subtitle = "网络策略与落盘位置")
                VSpace(AppTheme.spacing.xs)
                SettingSwitchRow(
                    title = "仅 Wi-Fi 下载",
                    desc = "移动网络下自动挂起任务",
                    checked = wifiOnly
                ) { v -> scope.launch { settings.setWifiOnly(v) } }
                Hairline()
                SettingSwitchRow(
                    title = "保存到系统相册",
                    desc = "关闭后仅保存在应用私有目录",
                    checked = saveAlbum
                ) { v -> scope.launch { settings.setSaveToAlbum(v) } }
                Hairline()
                VSpace(AppTheme.spacing.sm)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("最大并发任务数", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    StatusPill("$maxConcurrent", Tone.Brand)
                }
                var sliderValue by remember(maxConcurrent) { mutableFloatStateOf(maxConcurrent.toFloat()) }
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { scope.launch { settings.setMaxConcurrent(sliderValue.toInt()) } },
                    valueRange = 1f..6f,
                    steps = 4
                )
                Text(
                    "并发越高越快，也越容易被平台限速；建议 3",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            AppCard {
                CardHeader(
                    title = "监听范围",
                    subtitle = "未勾选任何平台时表示全部监听",
                    trailing = { StatusPill(if (enabledPlatforms.isEmpty()) "全部" else "${enabledPlatforms.size} 个", Tone.Brand) }
                )
                VSpace(AppTheme.spacing.xs)
                Platform.entries.filter { it != Platform.GENERIC }.forEach { p ->
                    val checked = enabledPlatforms.isEmpty() || enabledPlatforms.contains(p.id)
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
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                        )
                        Text(p.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            AppCard {
                CardHeader(
                    title = "登录态增强",
                    subtitle = "免登录只能拿到低清或封面"
                )
                VSpace(AppTheme.spacing.xs)
                Text(
                    "从浏览器复制对应站点的 Cookie 粘贴到下面，即可获取原画质。Cookie 只保存在本机。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VSpace(AppTheme.spacing.md)
                CookieStore.platformsNeedingCookie().forEach { p ->
                    var text by remember(p) { mutableStateOf(CookieStore.get(context, p) ?: "") }
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            CookieStore.put(context, p, it)
                        },
                        label = { Text("${p.displayName} Cookie") },
                        modifier = Modifier.fillMaxWidth().padding(bottom = AppTheme.spacing.sm),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(AppTheme.radius.control),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                }
            }
        }

        item {
            AppCard {
                CardHeader(title = "远端解析兜底", subtitle = "本地失败时自动降级")
                VSpace(AppTheme.spacing.xs)
                Text(
                    "本地解析会因平台风控随时失效。可自建 cobalt 或 yt-dlp 服务端，本地失败时自动调用。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VSpace(AppTheme.spacing.xs)
                SettingSwitchRow(
                    title = "启用远端解析",
                    desc = "仅在本机解析失败时使用",
                    checked = remoteEnabled
                ) { v ->
                    scope.launch {
                        settings.setRemoteEnabled(v)
                        ParserEngine.updateConfig(settings.parserConfig())
                    }
                }
                Hairline()
                VSpace(AppTheme.spacing.sm)
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
                    modifier = Modifier.fillMaxWidth().padding(bottom = AppTheme.spacing.sm),
                    singleLine = true,
                    shape = RoundedCornerShape(AppTheme.radius.control),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
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
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(AppTheme.radius.control),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }
        }

        item { UpdateSection() }
    }
}

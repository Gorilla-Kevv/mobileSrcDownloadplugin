package com.clipdown.app.ui.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clipdown.app.ClipDownApp
import com.clipdown.app.data.CookieStore
import com.clipdown.app.floatwindow.FloatingWindowService
import com.clipdown.app.ui.components.AppCard
import com.clipdown.app.ui.components.CardHeader
import com.clipdown.app.ui.components.Hairline
import com.clipdown.app.ui.components.NoticeBar
import com.clipdown.app.ui.components.PageHeader
import com.clipdown.app.ui.components.SettingSwitchRow
import com.clipdown.app.ui.components.StatusPill
import com.clipdown.app.ui.components.Tone
import com.clipdown.app.ui.components.VSpace
import com.clipdown.app.ui.components.WebLoginDialog
import androidx.compose.ui.text.font.FontFamily
import com.clipdown.app.sync.SyncManager
import com.clipdown.app.ui.components.BookmarkletGuideDialog
import com.clipdown.app.ui.components.SyncPairingDialog
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.app.update.UpdateSection
import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.model.Platform
import com.clipdown.parser.parsers.IgRiskGuard
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

    var hasOverlayPermission by remember { mutableStateOf(FloatingWindowService.hasPermission(context)) }
    var activeLoginPlatform by remember { mutableStateOf<Platform?>(null) }
    var showBookmarkletPlatform by remember { mutableStateOf<Platform?>(null) }
    val isSyncServerRunning by SyncManager.isServerRunning.collectAsStateWithLifecycle()
    val syncIp by SyncManager.localIp.collectAsStateWithLifecycle()
    val syncPort by SyncManager.port.collectAsStateWithLifecycle()
    val syncPin by SyncManager.pairingPin.collectAsStateWithLifecycle()
    var showSyncDialog by remember { mutableStateOf(false) }
    var cookieRefreshToken by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = FloatingWindowService.hasPermission(context)
                hasOverlayPermission = granted
                if (granted && floatEnabled) {
                    FloatingWindowService.tryStart(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                if (floatEnabled && !hasOverlayPermission) {
                    VSpace(AppTheme.spacing.xs)
                    NoticeBar(
                        text = "检测到悬浮窗权限未开启，桌面悬浮球无法显示。点此前往系统设置开启授权",
                        tone = Tone.Warning,
                        modifier = Modifier.clickable {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    )
                }
                VSpace(AppTheme.spacing.xs)
                SettingSwitchRow(
                    title = "启用悬浮球",
                    desc = "常驻屏幕边缘，点击可立即识别剪贴板",
                    checked = floatEnabled
                ) { v ->
                    scope.launch {
                        settings.setFloatEnabled(v)
                        if (v) {
                            if (FloatingWindowService.hasPermission(context)) {
                                FloatingWindowService.tryStart(context)
                            } else {
                                Toast.makeText(context, "请先授予悬浮窗权限以显示桌面悬浮球", Toast.LENGTH_LONG).show()
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        } else {
                            FloatingWindowService.stop(context)
                        }
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
            AppCard(shape = RectangleShape) {
                CardHeader(
                    title = "🖥️ 电脑端插件与跨端同步 (Edge / Chrome)",
                    subtitle = "共享登录态免风控 · 下载记录双向同步",
                    trailing = {
                        StatusPill(
                            text = if (isSyncServerRunning) "服务运行中" else "未开启",
                            tone = if (isSyncServerRunning) Tone.Success else Tone.Neutral,
                            shape = RectangleShape
                        )
                    }
                )
                VSpace(AppTheme.spacing.xs)
                Text(
                    "在 Edge / Chrome 浏览器安装 ClipDown 助手插件，可将电脑上已登录的 Instagram / 小红书等 Cookie 瞬间灌入手机，彻底解决手机端风控与 2FA 死循环；同时可双向同步下载记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VSpace(AppTheme.spacing.sm)

                SettingSwitchRow(
                    title = "启用局域网同步服务",
                    desc = if (isSyncServerRunning) "地址: http://${syncIp ?: "127.0.0.1"}:$syncPort · PIN: $syncPin" else "开启后电脑端插件可直连",
                    checked = isSyncServerRunning
                ) { enabled ->
                    if (enabled) {
                        SyncManager.startServer()
                    } else {
                        SyncManager.stopServer()
                    }
                }

                if (isSyncServerRunning) {
                    VSpace(AppTheme.spacing.xs)
                    Hairline()
                    VSpace(AppTheme.spacing.sm)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "配对码 PIN: $syncPin",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row {
                            OutlinedButton(
                                onClick = {
                                    SyncManager.regeneratePin()
                                    Toast.makeText(context, "已重新生成配对码", Toast.LENGTH_SHORT).show()
                                },
                                shape = RectangleShape,
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("重置 PIN", style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(AppTheme.spacing.xs))
                            Button(
                                onClick = { showSyncDialog = true },
                                shape = RectangleShape,
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("配对指引", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        item {
            AppCard(shape = RectangleShape) {
                CardHeader(
                    title = "登录态增强",
                    subtitle = "免登录常被限制，一键登录获取原画质"
                )
                VSpace(AppTheme.spacing.xs)
                Text(
                    "内置浏览器支持一键登录，成功后自动捕获并保存凭据，解决手动提取 Cookie 的难题。凭据仅保存在本机私有存储中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VSpace(AppTheme.spacing.md)

                CookieStore.platformsNeedingCookie().forEachIndexed { index, p ->
                    if (index > 0) {
                        VSpace(AppTheme.spacing.sm)
                        Hairline()
                        VSpace(AppTheme.spacing.sm)
                    }

                    var cookieText by remember(p, cookieRefreshToken) {
                        mutableStateOf(CookieStore.get(context, p) ?: "")
                    }
                    var showManualEdit by remember(p) { mutableStateOf(false) }
                    val hasCookie = cookieText.isNotBlank()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    p.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.width(AppTheme.spacing.xs))
                                StatusPill(
                                    text = if (hasCookie) "已登录" else "未登录",
                                    tone = if (hasCookie) Tone.Success else Tone.Neutral,
                                    shape = RectangleShape
                                )
                            }
                            if (p == Platform.INSTAGRAM) {
                                Text(
                                    "解决 Reel 视频解析失败与风控登录墙",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val remainMin = IgRiskGuard.remainingMinutes
                                if (remainMin > 0) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "当前处于风控冷却中（剩余约 $remainMin 分钟）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p == Platform.INSTAGRAM && IgRiskGuard.remainingMinutes > 0) {
                                OutlinedButton(
                                    onClick = {
                                        IgRiskGuard.reset()
                                        cookieRefreshToken++
                                        Toast.makeText(context, "已解除 Instagram 风控冷却", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RectangleShape,
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("解除冷却", style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.width(AppTheme.spacing.xs))
                            }
                            Button(
                                onClick = { activeLoginPlatform = p },
                                shape = RectangleShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (hasCookie) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
                                    contentColor = if (hasCookie) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    if (hasCookie) "重新登录" else "一键登录",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            if (hasCookie) {
                                Spacer(Modifier.width(AppTheme.spacing.xs))
                                OutlinedButton(
                                    onClick = {
                                        CookieStore.remove(context, p)
                                        cookieText = ""
                                        cookieRefreshToken++
                                        Toast.makeText(context, "已清除 ${p.displayName} 登录态", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RectangleShape,
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("清除", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (showManualEdit) "▼ 收起手动输入" else "▶ 手动查看/输入 Cookie",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { showManualEdit = !showManualEdit }
                        )
                        Text(
                            text = "🔗 手机浏览器书签秒提",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { showBookmarkletPlatform = p }
                        )
                    }

                    if (showManualEdit) {
                        OutlinedTextField(
                            value = cookieText,
                            onValueChange = {
                                cookieText = it
                                CookieStore.put(context, p, it)
                            },
                            label = { Text("${p.displayName} Cookie 文本") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = AppTheme.spacing.xs),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RectangleShape,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        )
                    }
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

    activeLoginPlatform?.let { platform ->
        WebLoginDialog(
            platform = platform,
            onDismiss = { activeLoginPlatform = null },
            onCookieSaved = {
                cookieRefreshToken++
                activeLoginPlatform = null
            }
        )
    }

    showBookmarkletPlatform?.let { platform ->
        BookmarkletGuideDialog(
            platform = platform,
            onDismiss = {
                showBookmarkletPlatform = null
                cookieRefreshToken++
            }
        )
    }

    if (showSyncDialog) {
        SyncPairingDialog(
            ip = syncIp,
            port = syncPort,
            pin = syncPin,
            isRunning = isSyncServerRunning,
            onDismiss = { showSyncDialog = false }
        )
    }
}

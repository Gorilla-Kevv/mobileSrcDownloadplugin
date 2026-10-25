package com.clipdown.app.ui.home

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import coil.request.ImageRequest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.clipdown.app.ClipDownApp
import com.clipdown.app.clip.ClipboardMonitor
import com.clipdown.app.clip.LinkCenter
import com.clipdown.app.floatwindow.FloatingWindowService
import com.clipdown.app.ui.components.AppCard
import com.clipdown.app.ui.components.AppChip
import com.clipdown.app.ui.components.CardHeader
import com.clipdown.app.ui.components.NoticeBar
import com.clipdown.app.ui.components.PageHeader
import com.clipdown.app.ui.components.PrimaryButton
import com.clipdown.app.ui.components.SecondaryButton
import com.clipdown.app.ui.components.StatusPill
import com.clipdown.app.ui.components.Tone
import com.clipdown.app.ui.components.VSpace
import com.clipdown.app.ui.components.WebLoginDialog
import com.clipdown.app.ui.profile.ProfileCenter
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.app.ui.theme.BrandContainer
import com.clipdown.app.ui.theme.OnBrandContainer
import com.clipdown.app.ui.theme.SuccessFg
import com.clipdown.downloader.DownloadController
import com.clipdown.downloader.DownloadService
import com.clipdown.downloader.model.DownloadStatus
import kotlinx.coroutines.flow.first
import com.clipdown.parser.core.LinkKind
import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.core.ProfileUrls
import com.clipdown.parser.core.UrlUtil
import com.clipdown.parser.model.MediaKind
import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.ParseResult
import com.clipdown.parser.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页：手动解析入口 + 权限引导 + 支持平台总览。
 *
 * 自动识别主要由悬浮窗完成，这里承担"随时可手动兜底"的职责。
 * 版式遵循统一设计令牌：页面标题 → 解析卡 → 结果卡 → 权限卡 → 平台卡，
 * 卡片间距、内边距、标题字号全部取自 [AppTheme]，不再各写各的。
 */
@Composable
fun HomeScreen(
    autoFocusParse: Boolean = false,
    onOpenProfile: () -> Unit = {},
    onOpenDownloads: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var parsing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ParseResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf(0)) }
    var isDuplicateParse by remember { mutableStateOf(false) }
    var loginPlatformPrompt by remember { mutableStateOf<Platform?>(null) }

    var overlayGranted by remember { mutableStateOf(FloatingWindowService.hasPermission(context)) }
    var accessibilityGranted by remember { mutableStateOf(AccessibilityState.isEnabled(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasOverlay = FloatingWindowService.hasPermission(context)
                overlayGranted = hasOverlay
                accessibilityGranted = AccessibilityState.isEnabled(context)
                if (hasOverlay) {
                    FloatingWindowService.tryStartIfEnabled(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val lastLink by LinkCenter.last.collectAsStateWithLifecycle()
    val tasks by DownloadController.repository().tasks.collectAsStateWithLifecycle()

    LaunchedEffect(autoFocusParse, lastLink?.url) {
        if (autoFocusParse) {
            val link = lastLink ?: return@LaunchedEffect
            input = link.url
            // 分享/VIEW 进来的也可能是主页链接：同样走主页链路，不能塞进单篇解析
            if (withContext(Dispatchers.IO) { ParserEngine.linkKind(link.url) } == LinkKind.PROFILE) {
                ProfileCenter.open(link.url)
                onOpenProfile()
                return@LaunchedEffect
            }
            val app = context.applicationContext as? ClipDownApp
            val seenBefore = app?.settings?.seenLinks?.first() ?: emptySet()
            val hasCompleted = DownloadController.repository().tasks.value.any {
                (it.sourceUrl == link.url || it.url == link.url) && it.status == DownloadStatus.COMPLETED
            }
            isDuplicateParse = link.url in seenBefore || hasCompleted
            app?.settings?.markLinkSeen(link.url)

            parsing = true
            error = null
            result = withContext(Dispatchers.IO) { ParserEngine.parseSafe(link.url).getOrNull() }
            parsing = false
            selected = result?.takeIf { it.isAlbumMultiSelect }?.media?.indices?.toSet() ?: setOf(0)
            if (result == null) error = "解析失败，可稍后重试或在设置中配置远端解析服务"
        }
    }

    fun parse(text: String) {
        // 输入可能是整段分享文案（标题+链接+引导语），先抽出真正的 URL——
        // 悬浮窗链路在 LinkCenter 里做过这一步，首页此前漏了，导致分享文案整段
        // 被当 URL 判平台而报"暂不支持该链接"
        val url = UrlUtil.extractFirst(text)
        if (url.isNullOrBlank()) {
            error = "未在输入中找到可识别的链接，请检查粘贴内容"
            return
        }
        scope.launch {
            // 博主主页链接走独立页面（主页是集合形态，不能混进单篇解析链路）
            if (withContext(Dispatchers.IO) { ParserEngine.linkKind(url) } == LinkKind.PROFILE) {
                parsing = true
                error = null
                ProfileCenter.open(url)
                parsing = false
                onOpenProfile()
                return@launch
            }
            val app = context.applicationContext as? ClipDownApp
            val seenBefore = app?.settings?.seenLinks?.first() ?: emptySet()
            val hasCompleted = DownloadController.repository().tasks.value.any {
                (it.sourceUrl == url || it.url == url) && it.status == DownloadStatus.COMPLETED
            }
            isDuplicateParse = url in seenBefore || hasCompleted
            app?.settings?.markLinkSeen(url)

            parsing = true
            error = null
            result = null
            selected = setOf(0)
            val r = withContext(Dispatchers.IO) { ParserEngine.parseSafe(url) }
            parsing = false
            if (r.isSuccess) {
                val parsed = r.getOrThrow()
                result = parsed
                selected = if (parsed.isAlbumMultiSelect) parsed.media.indices.toSet() else setOf(0)
                LinkCenter.publishResult(parsed)
            } else {
                val e = r.exceptionOrNull()
                error = (e as? ParseException)?.message ?: e?.message ?: "解析失败"
            }
        }
    }

    // 仅本地判断（不展开短链、不发网络）：用于输入框下方的提示条
    val isProfileInput = remember(input) {
        val url = UrlUtil.extractFirst(input)
        url != null && runCatching { ProfileUrls.match(url) != null }.getOrDefault(false)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
    ) {
        item {
            PageHeader(
                title = "剪存 ClipDown",
                subtitle = "复制链接后气泡会自动提示下载，也可以在这里手动粘贴解析"
            )
        }

        if (!overlayGranted) {
            item {
                AppCard(
                    shape = RectangleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(AppTheme.spacing.sm))
                        Text(
                            "悬浮球未显示（缺少悬浮窗权限）",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    VSpace(AppTheme.spacing.xs)
                    Text(
                        "检测到「悬浮窗权限」尚未开启，桌面悬浮球无法正常常驻显示。清理应用缓存/数据或系统重置后通常需要重新授权。\n授权后悬浮球将自动恢复，复制链接可在任意应用上一键识别下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    VSpace(AppTheme.spacing.md)
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Text("立即开启悬浮窗权限", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        item {
            AppCard {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "粘贴 Instagram / X / 小红书 / 抖音 链接",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(AppTheme.radius.control),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                VSpace(AppTheme.spacing.md)
                Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                    SecondaryButton(
                        text = "读取剪贴板",
                        icon = Icons.Default.ContentPaste,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val text = ClipboardMonitor.readAndSubmitForce()
                            if (!text.isNullOrBlank()) {
                                input = text
                                parse(text)
                            } else {
                                error = "剪贴板为空或当前无权限读取，请手动粘贴"
                            }
                        }
                    )
                    PrimaryButton(
                        text = "解析",
                        modifier = Modifier.weight(1f),
                        enabled = input.isNotBlank() && !parsing,
                        onClick = { parse(input) }
                    )
                }
                if (isProfileInput) {
                    VSpace(AppTheme.spacing.sm)
                    NoticeBar("识别到博主主页链接，点「解析」将打开主页页", Tone.Info)
                }
            }
        }

        item {
            AnimatedVisibility(visible = parsing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.xs),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(AppTheme.spacing.sm))
                    Text(
                        "解析中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        error?.let { msg ->
            item {
                Column {
                    NoticeBar(msg, Tone.Danger)
                    if (msg.contains("Instagram") && (msg.contains("Cookie") || msg.contains("登录") || msg.contains("Reel") || msg.contains("限制"))) {
                        VSpace(AppTheme.spacing.xs)
                        Button(
                            onClick = { loginPlatformPrompt = Platform.INSTAGRAM },
                            shape = RectangleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.fillMaxWidth().height(38.dp)
                        ) {
                            Text("打开内置浏览器一键登录 Instagram", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        result?.let { r ->
            item {
                val hasCompletedTask = remember(r.sourceUrl, tasks) {
                    val src = r.sourceUrl
                    !src.isNullOrBlank() && tasks.any {
                        (it.sourceUrl == src || it.url == src) && it.status == DownloadStatus.COMPLETED
                    }
                }
                val isDuplicate = isDuplicateParse || hasCompletedTask

                ResultCard(
                    result = r,
                    isDuplicate = isDuplicate,
                    selectedIndices = selected,
                    onToggle = { index ->
                        selected = if (r.isAlbumMultiSelect) {
                            if (index in selected) selected - index else selected + index
                        } else {
                            setOf(index)
                        }
                    },
                    onSetSelection = { selected = it },
                    onDownload = { indices ->
                        val items = indices.mapNotNull { r.media.getOrNull(it) }
                        if (items.isNotEmpty()) {
                            DownloadController.enqueueAll(items, r.platform, r.title, r.sourceUrl, r.coverUrl)
                            context.startService(DownloadService.intent(context, DownloadService.ACTION_RESUME))
                            Toast.makeText(
                                context,
                                "已加入下载队列（${items.size} 项），可在「下载」页面查看",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onOpenDownloads = onOpenDownloads
                )
            }
        }

        item { PermissionSection(overlayGranted = overlayGranted, accessibilityGranted = accessibilityGranted) }

        item { PlatformSection() }
    }

    loginPlatformPrompt?.let { platform ->
        WebLoginDialog(
            platform = platform,
            onDismiss = { loginPlatformPrompt = null },
            onCookieSaved = {
                loginPlatformPrompt = null
                error = null
                if (input.isNotBlank()) {
                    parse(input)
                }
            }
        )
    }
}

@Composable
private fun ResultCard(
    result: ParseResult,
    isDuplicate: Boolean = false,
    selectedIndices: Set<Int>,
    onToggle: (Int) -> Unit,
    onSetSelection: (Set<Int>) -> Unit,
    onDownload: (Set<Int>) -> Unit,
    onOpenDownloads: () -> Unit
) {
    val multiSelect = result.isAlbumMultiSelect
    val imageIndices = remember(result) {
        result.media.indices.filter { result.media[it].kind == MediaKind.IMAGE }.toSet()
    }

    // 记录已加入下载队列的索引与交互反馈，防止重复点击
    var enqueuedIndices by remember(result) { mutableStateOf<Set<Int>>(emptySet()) }
    var downloadFeedback by remember(result) { mutableStateOf<String?>(null) }
    val isCurrentSelectionEnqueued = remember(selectedIndices, enqueuedIndices) {
        selectedIndices.isNotEmpty() && selectedIndices.all { it in enqueuedIndices }
    }

    // 重复下载确认弹窗状态
    var showDuplicateConfirmDialog by remember { mutableStateOf(false) }
    var pendingDownloadIndices by remember { mutableStateOf<Set<Int>?>(null) }

    if (showDuplicateConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                showDuplicateConfirmDialog = false
                pendingDownloadIndices = null
            },
            title = {
                Text("重复下载提醒", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(
                    "检测到该作品此前已识别或下载过。确定要再次下载并保存新的副本吗？",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDownload = pendingDownloadIndices ?: selectedIndices
                        showDuplicateConfirmDialog = false
                        pendingDownloadIndices = null
                        if (toDownload.isNotEmpty()) {
                            onDownload(toDownload)
                            enqueuedIndices = enqueuedIndices + toDownload
                            downloadFeedback = "已成功加入下载队列（${toDownload.size} 项），后台下载中"
                        }
                    }
                ) {
                    Text("确定再次下载", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDuplicateConfirmDialog = false
                        pendingDownloadIndices = null
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    AppCard {
        // 作品信息：缩略图 + 标题 + 来源
        Row(verticalAlignment = Alignment.CenterVertically) {
            val coverUrl = remember(result) {
                result.coverUrl ?: result.media.firstOrNull { it.kind == MediaKind.IMAGE }?.url ?: result.media.firstOrNull()?.url
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(AppTheme.radius.thumb))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (result.media.any { it.kind == MediaKind.VIDEO }) Icons.Default.Movie else Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(28.dp)
                )
                if (!coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(coverUrl)
                            .apply {
                                result.coverHeaders?.forEach { (k, v) -> addHeader(k, v) }
                            }
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width(AppTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.title ?: "未命名作品",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                VSpace(AppTheme.spacing.xs)
                Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                    StatusPill(result.platform.displayName, Tone.Brand)
                    StatusPill(ParserEngine.sourceLabel(result.source), Tone.Neutral)
                }
            }
        }

        if (!result.warning.isNullOrBlank()) {
            VSpace(AppTheme.spacing.md)
            NoticeBar(result.warning!!, Tone.Warning)
        }

        if (isDuplicate) {
            VSpace(AppTheme.spacing.md)
            NoticeBar("提示：该作品此前已识别或下载过，再次下载将保存新的副本", Tone.Warning)
        }

        VSpace(AppTheme.spacing.lg)
        HairlineRow()

        // 资源选择
        VSpace(AppTheme.spacing.md)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("资源", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text(
                if (multiSelect) "已选 ${selectedIndices.size}/${result.media.size}" else "单选",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        VSpace(AppTheme.spacing.sm)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
            itemsIndexed(result.media) { index, item ->
                AppChip(
                    text = "${kindLabel(item.kind)} · ${item.quality}",
                    selected = index in selectedIndices,
                    onClick = { onToggle(index) }
                )
            }
        }

        if (multiSelect) {
            VSpace(AppTheme.spacing.xs)
            Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                TextButton(onClick = { onSetSelection(result.media.indices.toSet()) }) {
                    Text("全选", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(
                    onClick = { onSetSelection(imageIndices) },
                    enabled = imageIndices.isNotEmpty()
                ) {
                    Text("仅图片", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = { onSetSelection(emptySet()) }) {
                    Text("清空", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // 下载交互反馈提示
        if (!downloadFeedback.isNullOrBlank()) {
            VSpace(AppTheme.spacing.md)
            NoticeBar(downloadFeedback!!, Tone.Success)
        }

        VSpace(AppTheme.spacing.md)
        if (isCurrentSelectionEnqueued) {
            // 已加入队列：展示"重新下载"与"查看下载任务"，避免误重复点击
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)
            ) {
                SecondaryButton(
                    text = "重新下载",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (isDuplicate) {
                            pendingDownloadIndices = selectedIndices
                            showDuplicateConfirmDialog = true
                        } else {
                            val count = selectedIndices.size
                            onDownload(selectedIndices)
                            downloadFeedback = "已重新加入下载队列（$count 项）"
                        }
                    }
                )
                PrimaryButton(
                    text = "查看下载任务",
                    icon = Icons.Default.Download,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenDownloads
                )
            }
        } else {
            PrimaryButton(
                text = if (multiSelect) "下载所选 ${selectedIndices.size} 项" else "下载所选资源",
                icon = Icons.Default.Download,
                enabled = selectedIndices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (isDuplicate) {
                        pendingDownloadIndices = selectedIndices
                        showDuplicateConfirmDialog = true
                    } else {
                        val count = selectedIndices.size
                        onDownload(selectedIndices)
                        enqueuedIndices = enqueuedIndices + selectedIndices
                        downloadFeedback = "已成功加入下载队列（$count 项），后台下载中"
                    }
                }
            )
        }
    }
}

@Composable
private fun HairlineRow() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun PermissionSection(
    overlayGranted: Boolean,
    accessibilityGranted: Boolean
) {
    val context = LocalContext.current

    AppCard {
        CardHeader(title = "必要权限", subtitle = "开启后即可在复制链接时自动识别")
        VSpace(AppTheme.spacing.sm)
        PermissionRow(
            title = "悬浮窗",
            desc = "在其他应用上方弹出下载提示",
            granted = overlayGranted,
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                )
            }
        )
        PermissionRow(
            title = "无障碍服务",
            desc = "Android 10+ 后台读不到剪贴板，开启后可在复制时自动识别",
            granted = accessibilityGranted,
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            VSpace(2.dp)
            Text(
                desc,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(AppTheme.spacing.md))
        StatusPill(
            text = if (granted) "已开启" else "去开启",
            tone = if (granted) Tone.Success else Tone.Warning,
            modifier = Modifier.clickable(onClick = onClick)
        )
    }
}

@Composable
private fun PlatformSection() {
    val platforms = remember { Platform.entries.filter { it != Platform.GENERIC } }

    AppCard {
        CardHeader(title = "支持的平台", subtitle = "共 ${platforms.size} 个平台")
        VSpace(AppTheme.spacing.md)
        // 两列网格：比横向滚动更整齐，所有瓦片等宽等高
        platforms.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)
            ) {
                pair.forEach { p ->
                    PlatformTile(p, modifier = Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            VSpace(AppTheme.spacing.sm)
        }
    }
}

@Composable
private fun PlatformTile(platform: Platform, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AppTheme.radius.control))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BrandContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                platform.displayName.take(2).trim(),
                style = MaterialTheme.typography.labelLarge,
                color = OnBrandContainer
            )
        }
        Spacer(Modifier.width(AppTheme.spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                platform.displayName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (platform.loginRequired) "需登录态" else "公开可解析",
                style = MaterialTheme.typography.labelSmall,
                color = if (platform.loginRequired) MaterialTheme.colorScheme.onSurfaceVariant else SuccessFg
            )
        }
    }
}

private fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.VIDEO -> "视频"
    MediaKind.IMAGE -> "图片"
    MediaKind.AUDIO -> "音频"
    MediaKind.GALLERY -> "图集"
    MediaKind.UNKNOWN -> "资源"
}

/** 无障碍服务开关状态查询 */
private object AccessibilityState {
    fun isEnabled(context: android.content.Context): Boolean {
        val service = "${context.packageName}/${com.clipdown.app.clip.ClipAccessibilityService::class.java.name}"
        return runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.contains(service) == true
        }.getOrDefault(false)
    }
}

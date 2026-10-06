package com.clipdown.app.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
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
import com.clipdown.app.ui.profile.ProfileCenter
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.app.ui.theme.BrandContainer
import com.clipdown.app.ui.theme.OnBrandContainer
import com.clipdown.app.ui.theme.SuccessFg
import com.clipdown.downloader.DownloadController
import com.clipdown.downloader.DownloadService
import com.clipdown.parser.core.LinkKind
import com.clipdown.parser.core.ParserEngine
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
fun HomeScreen(autoFocusParse: Boolean = false, onOpenProfile: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var parsing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ParseResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf(0)) }

    val lastLink by LinkCenter.last.collectAsStateWithLifecycle()

    LaunchedEffect(autoFocusParse, lastLink?.url) {
        if (autoFocusParse) {
            val link = lastLink ?: return@LaunchedEffect
            input = link.url
            parsing = true
            error = null
            result = withContext(Dispatchers.IO) { ParserEngine.parseSafe(link.url).getOrNull() }
            parsing = false
            selected = setOf(0)
            if (result == null) error = "解析失败，可稍后重试或在设置中配置远端解析服务"
        }
    }

    fun parse(text: String) {
        val url = text.trim()
        if (url.isBlank()) return
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
            parsing = true
            error = null
            result = null
            selected = setOf(0)
            val r = withContext(Dispatchers.IO) { ParserEngine.parseSafe(url) }
            parsing = false
            if (r.isSuccess) {
                result = r.getOrThrow()
                LinkCenter.publishResult(r.getOrThrow())
            } else {
                val e = r.exceptionOrNull()
                error = (e as? ParseException)?.message ?: e?.message ?: "解析失败"
            }
        }
    }

    val isProfileInput = remember(input) {
        input.isNotBlank() && runCatching { ParserEngine.linkKind(input) == LinkKind.PROFILE }.getOrDefault(false)
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
            item { NoticeBar(msg, Tone.Danger) }
        }

        result?.let { r ->
            item {
                ResultCard(
                    result = r,
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
                        DownloadController.enqueueAll(items, r.platform, r.title, r.sourceUrl)
                        context.startService(DownloadService.intent(context, DownloadService.ACTION_RESUME))
                    }
                )
            }
        }

        item { PermissionSection() }

        item { PlatformSection() }
    }
}

@Composable
private fun ResultCard(
    result: ParseResult,
    selectedIndices: Set<Int>,
    onToggle: (Int) -> Unit,
    onSetSelection: (Set<Int>) -> Unit,
    onDownload: (Set<Int>) -> Unit
) {
    val multiSelect = result.isAlbumMultiSelect
    val imageIndices = remember(result) {
        result.media.indices.filter { result.media[it].kind == MediaKind.IMAGE }.toSet()
    }

    AppCard {
        // 作品信息：缩略图 + 标题 + 来源
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = result.coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(AppTheme.radius.thumb))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
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

        VSpace(AppTheme.spacing.md)
        PrimaryButton(
            text = if (multiSelect) "下载所选 ${selectedIndices.size} 项" else "下载所选资源",
            icon = Icons.Default.Download,
            enabled = selectedIndices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onClick = { onDownload(selectedIndices) }
        )
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
private fun PermissionSection() {
    val context = LocalContext.current
    val overlayGranted = remember { mutableStateOf(FloatingWindowService.hasPermission(context)) }

    LaunchedEffect(Unit) { overlayGranted.value = FloatingWindowService.hasPermission(context) }

    AppCard {
        CardHeader(title = "必要权限", subtitle = "开启后即可在复制链接时自动识别")
        VSpace(AppTheme.spacing.sm)
        PermissionRow(
            title = "悬浮窗",
            desc = "在其他应用上方弹出下载提示",
            granted = overlayGranted.value,
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
            granted = AccessibilityState.isEnabled(context),
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

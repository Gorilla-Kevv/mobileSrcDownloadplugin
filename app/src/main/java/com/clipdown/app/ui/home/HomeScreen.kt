package com.clipdown.app.ui.home

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.clipdown.app.clip.ClipboardMonitor
import com.clipdown.app.clip.LinkCenter
import com.clipdown.app.clip.LinkSource
import com.clipdown.app.floatwindow.FloatingWindowService
import com.clipdown.app.ui.theme.SeedBlue
import com.clipdown.downloader.DownloadController
import com.clipdown.downloader.DownloadService
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
 * 自动识别主要由悬浮窗完成，这里承担"随时可手动兜底"的职责：
 * 用户在任何时刻都能粘贴链接直接解析下载。
 */
@Composable
fun HomeScreen(autoFocusParse: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf("") }
    var parsing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ParseResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableIntStateOf(0) }

    val lastLink by LinkCenter.last.collectAsStateWithLifecycle()

    // 从分享或悬浮窗进来时，直接解析最近一条链接
    LaunchedEffect(autoFocusParse, lastLink?.url) {
        if (autoFocusParse) {
            val link = lastLink ?: return@LaunchedEffect
            input = link.url
            parsing = true
            error = null
            result = withContext(Dispatchers.IO) { ParserEngine.parseSafe(link.url).getOrNull() }
            parsing = false
            if (result == null) error = "解析失败，可稍后重试或在设置中配置远端解析服务"
        }
    }

    fun parse(text: String) {
        val url = text.trim()
        if (url.isBlank()) return
        scope.launch {
            parsing = true
            error = null
            result = null
            selected = 0
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("剪存 ClipDown", style = MaterialTheme.typography.titleLarge)
            Text(
                "复制任意受支持平台的链接，悬浮窗会自动提示下载；也可以在这里手动粘贴。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴 Instagram / X / 小红书 / 抖音 链接") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val text = ClipboardMonitor.readAndSubmitForce()
                                if (!text.isNullOrBlank()) {
                                    input = text
                                    parse(text)
                                } else {
                                    error = "剪贴板为空或当前无权限读取，请手动粘贴"
                                }
                            },
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("读取剪贴板")
                        }
                        Spacer(Modifier.width(10.dp))
                        Button(
                            onClick = { parse(input) },
                            enabled = input.isNotBlank() && !parsing,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SeedBlue)
                        ) {
                            Text("解析")
                        }
                    }
                }
            }
        }

        item {
            AnimatedVisibility(visible = parsing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("解析中…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        error?.let { msg ->
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFECEC))
                ) {
                    Text(
                        text = msg,
                        modifier = Modifier.padding(14.dp),
                        color = Color(0xFFB3261E),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        result?.let { r ->
            item {
                ResultCard(
                    result = r,
                    selected = selected,
                    onSelect = { selected = it },
                    onDownload = { index ->
                        val item = r.media[index]
                        DownloadController.enqueue(item, r.platform, r.title)
                        context.startService(DownloadService.intent(context, DownloadService.ACTION_RESUME))
                    }
                )
            }
        }

        item {
            PermissionSection()
        }

        item {
            PlatformSection()
        }
    }
}

@Composable
private fun ResultCard(
    result: ParseResult,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDownload: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = result.coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.05f))
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        result.title ?: "未命名作品",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${result.platform.displayName} · ${ParserEngine.sourceLabel(result.source)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }

            if (!result.warning.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    result.warning!!,
                    color = Color(0xFFB26A00),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(Modifier.height(14.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(result.media) { index, item ->
                    val isSelected = index == selected
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) SeedBlue else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { onSelect(index) }
                    ) {
                        Text(
                            text = "${kindLabel(item.kind)} · ${item.quality}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = { onDownload(selected) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SeedBlue)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("下载所选资源")
            }
        }
    }
}

@Composable
private fun PermissionSection() {
    val context = LocalContext.current
    val overlayGranted = remember { mutableStateOf(FloatingWindowService.hasPermission(context)) }

    LaunchedEffect(Unit) { overlayGranted.value = FloatingWindowService.hasPermission(context) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("必要权限", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            PermissionRow(
                title = "悬浮窗",
                desc = "用于在其他应用上方弹出下载提示",
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
                desc = "Android 10+ 后台无法读取剪贴板，开启后可在复制时自动识别",
                granted = AccessibilityState.isEnabled(context),
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (granted) Color(0xFF2EB872).copy(alpha = 0.14f) else Color(0xFFE5484D).copy(alpha = 0.12f))
                .border(1.dp, if (granted) Color(0xFF2EB872) else Color(0xFFE5484D), RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (granted) "已开启" else "去开启",
                color = if (granted) Color(0xFF1E7A4C) else Color(0xFFB3261E),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun PlatformSection() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("支持的平台", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Platform.entries.filter { it != Platform.GENERIC }.forEach { p ->
                    Column(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(p.displayName.take(2), style = MaterialTheme.typography.titleMedium, color = SeedBlue)
                        Spacer(Modifier.height(4.dp))
                        Text(p.displayName, style = MaterialTheme.typography.labelMedium)
                        if (p.loginRequired) {
                            Text("需登录态", style = MaterialTheme.typography.labelMedium, color = Color(0xFFB26A00))
                        }
                    }
                }
            }
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

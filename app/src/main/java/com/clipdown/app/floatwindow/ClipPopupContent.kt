package com.clipdown.app.floatwindow

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.clipdown.app.R
import com.clipdown.app.ui.theme.GlassBase
import com.clipdown.app.ui.theme.SeedBlue
import com.clipdown.parser.model.MediaKind

/**
 * 悬浮弹窗内容。
 *
 * 视觉：整屏容器只做"毛玻璃底 + 轻微压暗"，真正的卡片是一块半透明磨砂面板——
 * 在 Android 12+ 由系统级 `FLAG_BLUR_BEHIND` 提供真实背景模糊，
 * 低版本退化为半透明深色遮罩，观感一致但无实时模糊。
 */
@Composable
fun ClipPopupContent(
    state: PopupUiState,
    blurSupported: Boolean,
    remainSeconds: Int,
    onRecognize: () -> Unit,
    onSelect: (Int) -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit
) {
    val visible = state !is PopupUiState.Hidden
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 3 },
        exit = fadeOut() + slideOutVertically { it / 3 }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (blurSupported) Color(0x1F000000) else Color(0xAA0A0E1A))
                .clickable(enabled = true, onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth(
                        when (state) {
                            is PopupUiState.Mini -> 0.55f
                            is PopupUiState.Downloads -> 0.78f
                            else -> 0.92f
                        }
                    )
                    .clickable(enabled = true, onClick = {})
            ) {
                when (state) {
                    is PopupUiState.Mini -> MiniBody(state, onRecognize, onDismiss)
                    is PopupUiState.Loading -> LoadingBody(state.link.platform.displayName, remainSeconds, onDismiss)
                    is PopupUiState.Ready -> ReadyBody(state, remainSeconds, onSelect, onDownload, onOpenApp, onDismiss)
                    is PopupUiState.Failed -> FailedBody(state, onRetry, onDismiss)
                    is PopupUiState.Downloads -> DownloadsBody(state, onDismiss)
                    PopupUiState.Hidden -> Unit
                }
            }
        }
    }
}

/**
 * 毛玻璃卡片。
 *
 * 由三层叠加构成：半透明底色（决定"玻璃"的色调）+ 1dp 高光描边（玻璃边缘）+ 内容。
 * 真实模糊由窗口层提供，这里只负责质感。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = GlassBase.copy(alpha = 0.62f),
        tonalElevation = 12.dp,
        shadowElevation = 24.dp,
        content = content
    )
}

@Composable
private fun HeaderRow(
    title: String,
    subtitle: String,
    remainSeconds: Int,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(SeedBlue.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.take(1),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(subtitle, color = Color.White.copy(0.62f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        if (remainSeconds > 0) {
            Text("${remainSeconds}s", color = Color.White.copy(0.5f), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White.copy(0.8f))
        }
    }
}

@Composable
private fun MiniBody(
    state: PopupUiState.Mini,
    onRecognize: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "收起", tint = Color.White.copy(0.8f))
            }
        }
        Image(
            painter = painterResource(R.drawable.bubble_logo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .border(1.dp, Color.White.copy(0.2f), CircleShape)
        )
        Spacer(Modifier.height(10.dp))
        Text("剪存 ClipDown", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.hint ?: "复制链接后，点下方按钮识别",
            color = if (state.hint != null) Color(0xFFFFB020) else Color.White.copy(0.6f),
            style = MaterialTheme.typography.labelMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        if (state.recognizing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = SeedBlue)
                Spacer(Modifier.width(8.dp))
                Text("正在识别剪贴板…", color = Color.White.copy(0.75f), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Button(
                onClick = onRecognize,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SeedBlue),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("识别链接")
            }
        }
    }
}

@Composable
private fun LoadingBody(platformName: String, remainSeconds: Int, onDismiss: () -> Unit) {
    Column(modifier = Modifier.padding(18.dp)) {
        HeaderRow(platformName, "正在解析链接…", remainSeconds, onDismiss)
        Spacer(Modifier.height(22.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = SeedBlue
            )
            Spacer(Modifier.width(12.dp))
            Text("解析中", color = Color.White.copy(0.75f), style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ReadyBody(
    state: PopupUiState.Ready,
    remainSeconds: Int,
    onSelect: (Int) -> Unit,
    onDownload: () -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit
) {
    val result = state.result
    Column(modifier = Modifier.padding(18.dp)) {
        HeaderRow(
            title = result.platform.displayName,
            subtitle = "已解析 ${result.media.size} 个资源",
            remainSeconds = remainSeconds,
            onDismiss = onDismiss
        )

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = result.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(0.08f))
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title ?: "未命名作品",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!result.author.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "@${result.author}",
                        color = Color.White.copy(0.6f),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
                state.selected?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it.subtitle(),
                        color = Color.White.copy(0.7f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        if (!result.warning.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = result.warning!!,
                color = Color(0xFFFFB020),
                style = MaterialTheme.typography.labelMedium
            )
        }

        Spacer(Modifier.height(14.dp))

        if (state.multiSelect) {
            // 图集/多资源：快捷操作（阶段 18）
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    result.media.indices.forEach { if (it !in state.selectedIndices) onSelect(it) }
                }) { Text("全选", color = SeedBlue, style = MaterialTheme.typography.labelLarge) }
                TextButton(onClick = {
                    state.selectedIndices.toList().sortedDescending().forEach { if (result.media[it].kind != MediaKind.VIDEO) onSelect(it) }
                }) { Text("仅视频", color = SeedBlue, style = MaterialTheme.typography.labelLarge) }
                TextButton(onClick = {
                    state.selectedIndices.toList().forEach { onSelect(it) }
                }) { Text("清空", color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelLarge) }
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            itemsIndexed(result.media) { index, item ->
                val selected = index in state.selectedIndices
                QualityChip(
                    label = "${kindLabel(item.kind)} · ${item.quality}",
                    selected = selected,
                    onClick = { onSelect(index) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (state.downloading) {
            // 下载进度：弹窗内闭环（进度条/合并中/完成/失败）
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    state.downloadDone -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF2EB872),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("下载完成", color = Color(0xFF2EB872), style = MaterialTheme.typography.titleSmall)
                    }
                    state.downloadError != null -> Text(
                        state.downloadError,
                        color = Color(0xFFFFB020),
                        style = MaterialTheme.typography.labelMedium
                    )
                    else -> Column {
                        LinearProgressIndicator(
                            progress = { (state.downloadPercent ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = SeedBlue,
                            trackColor = Color.White.copy(0.12f)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when (state.downloadPercent) {
                                99 -> "正在合并音视频…"
                                else -> "下载中 ${state.downloadPercent ?: 0}%"
                            },
                            color = Color.White.copy(0.7f),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "点击空白处收起弹窗，下载将在后台继续",
                            color = Color.White.copy(0.45f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onOpenApp,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SeedBlue.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp), tint = SeedBlue)
                    Spacer(Modifier.width(6.dp))
                    Text("跳转至剪存应用", color = Color.White.copy(0.92f), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SeedBlue)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.multiSelect) "下载所选 ${state.selectedIndices.size} 项" else "开始下载")
                }
            }
        }
    }
}

@Composable
private fun FailedBody(
    state: PopupUiState.Failed,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.padding(18.dp)) {
        HeaderRow(
            title = state.link.platform.displayName,
            subtitle = "解析失败",
            remainSeconds = 0,
            onDismiss = onDismiss
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = state.message,
            color = Color.White.copy(0.82f),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text("知道了", color = Color.White.copy(0.7f))
            }
            if (state.retryable) {
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SeedBlue)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重试")
                }
            }
        }
    }
}

@Composable
private fun DownloadsBody(
    state: PopupUiState.Downloads,
    onDismiss: () -> Unit
) {
    Column(modifier = Modifier.padding(18.dp)) {
        HeaderRow(
            title = "下载任务",
            subtitle = "${state.tasks.size} 个进行中",
            remainSeconds = 0,
            onDismiss = onDismiss
        )
        Spacer(Modifier.height(10.dp))
        state.tasks.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(SeedBlue.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = row.platformName.take(1),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (row.percent ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = SeedBlue,
                        trackColor = Color.White.copy(0.12f)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = buildList {
                            add(
                                when (row.percent) {
                                    99 -> "正在合并音视频…"
                                    else -> "下载中 ${row.percent ?: 0}%"
                                }
                            )
                            row.sizeText?.let { add(it) }
                        }.joinToString(" · "),
                        color = Color.White.copy(0.65f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "点击空白处收起，下载在后台继续",
            color = Color.White.copy(0.45f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) SeedBlue.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f)
    val fg = if (selected) Color.White else Color.White.copy(alpha = 0.78f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else Color.White.copy(0.12f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.VIDEO -> "视频"
    MediaKind.IMAGE -> "图片"
    MediaKind.AUDIO -> "音频"
    MediaKind.GALLERY -> "图集"
    MediaKind.UNKNOWN -> "资源"
}

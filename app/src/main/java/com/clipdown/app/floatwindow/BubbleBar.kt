package com.clipdown.app.floatwindow

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clipdown.app.R
import com.clipdown.app.ui.theme.SeedBlue
import com.clipdown.downloader.model.DownloadStatus
import com.clipdown.parser.model.MediaKind

/**
 * 气泡侧竖向延伸条内容（修复 12：与气泡同窗口一体化，从气泡边缘"生长"，物理上不重叠）。
 * barOnTop=true 时该内容渲染在气泡圆上方。
 */

@Composable
internal fun BarBody(
    state: PopupUiState,
    onRecognize: () -> Unit,
    onConfirmParse: (com.clipdown.app.clip.DetectedLink) -> Unit,
    onCollapse: () -> Unit,
    onSelect: (Int) -> Unit,
    onDownload: () -> Unit,
    onCloseBubble: () -> Unit,
    onClearFinished: () -> Unit,
    onOpenTask: (String) -> Unit
) {
    when (state) {
        is PopupUiState.Mini -> MiniBarBody(state, onRecognize, onConfirmParse)
        is PopupUiState.Ready -> AlbumBarBody(state, onCollapse, onSelect, onDownload)
        is PopupUiState.Panel -> PanelBarBody(state, onCollapse, onCloseBubble, onClearFinished, onOpenTask)
        else -> Unit
    }
}

/** 平台色点：面板行前的品牌标识（未登记平台落主色） */
private fun platformDotColor(platformId: String): Color = when (platformId) {
    "instagram" -> Color(0xFFE1306C)
    "x" -> Color(0xFF1DA1F2)
    "xiaohongshu" -> Color(0xFFFF2442)
    "youtube" -> Color(0xFFFF0000)
    "bilibili" -> Color(0xFF00A1D6)
    "tiktok" -> Color(0xFF00F2EA)
    "douyin" -> Color(0xFFFE2C55)
    else -> SeedBlue
}

/** 面板行状态文案：合并中/失败原因/类别+百分比 */
private fun panelRowStatus(row: DownloadRow): String = when {
    row.status == DownloadStatus.FAILED -> "失败${row.error?.let { "：$it" } ?: ""}"
    row.status == DownloadStatus.CANCELED -> "已取消"
    row.status == DownloadStatus.COMPLETED -> "已完成"
    row.status == DownloadStatus.MERGING -> "合并中"
    else -> "${row.kindLabel} ${row.percent?.let { "$it%" } ?: "0%"}"
}

/**
 * 多功能面板竖条（阶段 46）：长按气泡呼出。
 * 头部聚合行 + 聚合进度条；任务按 taskId 逐行实时刷新；
 * 底部「关闭气泡」（危险色，替代原长按即关）+「清空已完成」；
 * 已完成行点行打开文件。不做暂停/重试（沿用下载页）。
 */
@Composable
private fun PanelBarBody(
    state: PopupUiState.Panel,
    onCollapse: () -> Unit,
    onCloseBubble: () -> Unit,
    onClearFinished: () -> Unit,
    onOpenTask: (String) -> Unit
) {
    val active = state.tasks.count { it.status.isActive }
    val done = state.tasks.count { it.status == DownloadStatus.COMPLETED }
    val hasFinished = state.tasks.any { it.status.isTerminal }

    Column(modifier = Modifier.width(64.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("下载 $active", color = Color.White, style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = onCollapse, modifier = Modifier.size(16.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "收起面板",
                    tint = Color.White.copy(0.85f),
                    modifier = Modifier.size(11.dp)
                )
            }
        }
        Text("已完成 $done", color = Color.White.copy(0.6f), style = MaterialTheme.typography.labelSmall)
        LinearProgressIndicator(
            progress = { (state.aggregatePercent ?: 0) / 100f },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = SeedBlue,
            trackColor = Color.White.copy(0.12f)
        )
        Spacer(Modifier.height(4.dp))
        if (state.tasks.isEmpty()) {
            Text(
                "暂无下载任务",
                color = Color.White.copy(0.5f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(state.tasks) { row ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(0.06f))
                            .then(
                                if (row.status == DownloadStatus.COMPLETED) {
                                    Modifier.clickable { onOpenTask(row.taskId) }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(platformDotColor(row.platformId))
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                row.title,
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            panelRowStatus(row),
                            color = when {
                                row.status == DownloadStatus.FAILED -> Color(0xFFE5484D)
                                row.status == DownloadStatus.COMPLETED -> Color(0xFF2EB872)
                                else -> Color.White.copy(0.6f)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        IconButton(
            onClick = onCloseBubble,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFE5484D), RoundedCornerShape(10.dp))
        ) {
            Icon(Icons.Default.Close, contentDescription = "关闭气泡", tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Text(
            "关闭气泡",
            color = Color(0xFFE5484D),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp
        )
        TextButton(
            onClick = onClearFinished,
            enabled = hasFinished,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 0.dp)
        ) {
            Text(
                "清空已完成",
                color = if (hasFinished) Color.White.copy(0.8f) else Color.White.copy(0.3f),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp
            )
        }
    }
}

/** Mini 竖条：剪贴板空闲/识别失败时的提示与识别入口；confirmLink 非空时变为"再次解析"确认 */
@Composable
private fun MiniBarBody(
    state: PopupUiState.Mini,
    onRecognize: () -> Unit,
    onConfirmParse: (com.clipdown.app.clip.DetectedLink) -> Unit
) {
    val confirm = state.confirmLink
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp)
    ) {
        Spacer(Modifier.height(2.dp))
        Text("剪存", color = Color.White, style = MaterialTheme.typography.labelMedium)
        if (state.hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                state.hint!!,
                color = Color(0xFFFFB020),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 4,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Spacer(Modifier.height(6.dp))
        IconButton(
            onClick = { if (confirm != null) onConfirmParse(confirm) else onRecognize() },
            modifier = Modifier
                .fillMaxWidth()
                .background(SeedBlue, RoundedCornerShape(12.dp))
        ) {
            Icon(
                if (confirm != null) Icons.Default.Refresh else Icons.Default.Search,
                contentDescription = if (confirm != null) "再次解析" else "识别",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 图集竖条：默认全选；点"全选"取消勾选=向下展开缩略图列逐张挑选，重新勾选=全选并收起。
 * 点缩略图右上角的 × 收回竖条、只剩气泡。
 */
@Composable
private fun AlbumBarBody(
    state: PopupUiState.Ready,
    onCollapse: () -> Unit,
    onSelect: (Int) -> Unit,
    onDownload: () -> Unit
) {
    val result = state.result
    var expanded by remember { mutableStateOf(false) }
    val allSelected = result.media.isNotEmpty() && state.selectedIndices.size == result.media.size

    Column(modifier = Modifier.width(64.dp)) {
        Box {
            AsyncImage(
                model = result.media.firstOrNull()?.url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(0.08f))
            )
            // 收回竖条：回到只有气泡的状态（问题 7 反馈的入口找回）
            IconButton(
                onClick = onCollapse,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "收回",
                    tint = Color.White.copy(0.85f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "图集 ${result.media.size} 张",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        Text(
            "已选 ${state.selectedIndices.size}",
            color = SeedBlue,
            style = MaterialTheme.typography.labelSmall
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    if (allSelected) {
                        state.selectedIndices.toList().forEach { onSelect(it) }
                        expanded = true
                    } else {
                        result.media.indices.forEach { if (it !in state.selectedIndices) onSelect(it) }
                        expanded = false
                    }
                }
                .padding(vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (allSelected) SeedBlue else Color.Transparent)
                    .border(1.5.dp, if (allSelected) SeedBlue else Color.White.copy(0.4f), RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (allSelected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
            Text("全选", color = Color.White.copy(0.85f), style = MaterialTheme.typography.labelSmall)
        }

        IconButton(
            onClick = onDownload,
            enabled = state.selectedIndices.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .background(SeedBlue.copy(alpha = if (state.selectedIndices.isEmpty()) 0.35f else 1f), RoundedCornerShape(10.dp))
        ) {
            Icon(Icons.Default.Download, contentDescription = "下载所选", tint = Color.White, modifier = Modifier.size(16.dp))
        }

        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 0.dp)
        ) {
            Text(if (expanded) "收起" else "挑图", color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelSmall)
        }

        AnimatedVisibility(visible = expanded) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(1),
                modifier = Modifier.heightIn(max = 260.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(result.media.size) { index ->
                    val selected = index in state.selectedIndices
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) SeedBlue else Color.White.copy(0.1f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onSelect(index) }
                    ) {
                        AsyncImage(
                            model = result.media[index].url,
                            contentDescription = "第 ${index + 1} 张",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        )
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(13.dp)
                                    .background(SeedBlue, CircleShape)
                                    .padding(2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

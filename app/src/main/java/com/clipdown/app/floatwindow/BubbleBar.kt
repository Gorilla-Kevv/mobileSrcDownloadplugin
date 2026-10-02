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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import coil.compose.AsyncImage
import com.clipdown.app.R
import com.clipdown.app.ui.theme.SeedBlue
import com.clipdown.parser.model.MediaKind

/**
 * 气泡侧竖向延伸条内容（修复 12：与气泡同窗口一体化，从气泡边缘"生长"，物理上不重叠）。
 * barOnTop=true 时该内容渲染在气泡圆上方。
 */

@Composable
internal fun BarBody(
    state: PopupUiState,
    onRecognize: () -> Unit,
    onSelect: (Int) -> Unit,
    onDownload: () -> Unit
) {
    when (state) {
        is PopupUiState.Mini -> MiniBarBody(state, onRecognize)
        is PopupUiState.Ready -> AlbumBarBody(state, onSelect, onDownload)
        else -> Unit
    }
}

/** Mini 竖条：剪贴板空闲/识别失败时的提示与识别入口 */
@Composable
private fun MiniBarBody(state: PopupUiState.Mini, onRecognize: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(2.dp))
        Text("剪存", color = Color.White, style = MaterialTheme.typography.labelMedium)
        if (state.hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                state.hint!!,
                color = Color(0xFFFFB020),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 3,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        Spacer(Modifier.height(6.dp))
        IconButton(
            onClick = onRecognize,
            modifier = Modifier
                .fillMaxWidth()
                .background(SeedBlue, RoundedCornerShape(12.dp))
        ) {
            Icon(Icons.Default.Search, contentDescription = "识别", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

/** 图集竖条：默认全选；全选行取消=向下展开缩略图列逐张挑选 */
@Composable
private fun AlbumBarBody(
    state: PopupUiState.Ready,
    onSelect: (Int) -> Unit,
    onDownload: () -> Unit
) {
    val result = state.result
    var expanded by remember { mutableStateOf(false) }
    val allSelected = result.media.isNotEmpty() && state.selectedIndices.size == result.media.size

    Column {
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

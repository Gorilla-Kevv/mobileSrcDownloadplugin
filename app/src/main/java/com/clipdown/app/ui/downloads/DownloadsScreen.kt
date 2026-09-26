package com.clipdown.app.ui.downloads

import android.content.Intent
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clipdown.downloader.DownloadController
import com.clipdown.downloader.model.DownloadStatus
import com.clipdown.downloader.model.ProgressEvent
import com.clipdown.downloader.model.TaskEntity
import com.clipdown.parser.model.MediaItem
import com.clipdown.app.ui.theme.SeedBlue
import kotlinx.coroutines.delay

/**
 * 下载管理页。
 *
 * 列表数据来自任务仓储（数据库快照），实时进度来自引擎的进度流：
 * 前者保证刷新/重启后状态一致，后者保证进度条流畅，两者叠加显示。
 */
@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val tasks by DownloadController.repository().tasks.collectAsStateWithLifecycle()
    val progressMap = remember { mutableStateMapOf<String, ProgressEvent>() }

    LaunchedEffect(Unit) {
        DownloadController.engine().progress.collect { progressMap[it.taskId] = it }
    }
    // 进度每 500ms 落库，这里按 1s 刷新一次列表快照，成本可忽略
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            DownloadController.repository().refresh()
        }
    }

    if (tasks.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("还没有下载任务", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "复制一条链接，悬浮窗会提示下载",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(tasks, key = { it.id }) { task ->
            TaskRow(
                task = task,
                progress = progressMap[task.id],
                onPause = { DownloadController.pause(task.id) },
                onResume = { DownloadController.resume(task.id) },
                onDelete = {
                    DownloadController.repository().delete(task.id)
                },
                onOpen = {
                    task.localUri?.let {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(android.net.Uri.parse(it), task.mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskEntity,
    progress: ProgressEvent?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        statusText(task, progress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                StatusChip(task.status)
            }

            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PENDING) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = SeedBlue
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpen, enabled = task.status == DownloadStatus.COMPLETED) {
                    Text("打开")
                }
                Spacer(Modifier.weight(1f))
                when (task.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.MERGING -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Pause, contentDescription = "暂停")
                        }
                    }
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "继续")
                        }
                    }
                    else -> Unit
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: DownloadStatus) {
    val (text, color) = when (status) {
        DownloadStatus.PENDING -> "等待中" to Color(0xFF8A8F98)
        DownloadStatus.DOWNLOADING -> "下载中" to SeedBlue
        DownloadStatus.PAUSED -> "已暂停" to Color(0xFFB26A00)
        DownloadStatus.MERGING -> "处理中" to SeedBlue
        DownloadStatus.COMPLETED -> "已完成" to Color(0xFF1E7A4C)
        DownloadStatus.FAILED -> "失败" to Color(0xFFB3261E)
        DownloadStatus.CANCELED -> "已取消" to Color(0xFF8A8F98)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

private fun statusText(task: TaskEntity, progress: ProgressEvent?): String {
    val speed = progress?.speedBytesPerSec ?: 0L
    val downloaded = progress?.downloadedBytes ?: task.downloadedBytes
    val total = progress?.totalBytes ?: task.totalBytes
    return when (task.status) {
        DownloadStatus.DOWNLOADING -> "${MediaItem.formatSize(downloaded)} / ${MediaItem.formatSize(total)}" +
            if (speed > 0) " · ${formatSpeed(speed)}" else ""
        DownloadStatus.COMPLETED -> "已完成 · ${MediaItem.formatSize(total)}"
        DownloadStatus.FAILED -> task.errorMessage ?: "下载失败"
        DownloadStatus.PAUSED -> "已暂停 · ${MediaItem.formatSize(downloaded)}"
        else -> task.status.name
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024.0
    return if (kb >= 1024) String.format("%.1f MB/s", kb / 1024) else String.format("%.0f KB/s", kb)
}

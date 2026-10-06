package com.clipdown.app.ui.downloads

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clipdown.app.ui.components.AppCard
import com.clipdown.app.ui.components.EmptyState
import com.clipdown.app.ui.components.PageHeader
import com.clipdown.app.ui.components.StatusPill
import com.clipdown.app.ui.components.Tone
import com.clipdown.app.ui.components.VSpace
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.downloader.DownloadController
import com.clipdown.downloader.model.DownloadStatus
import com.clipdown.downloader.model.ProgressEvent
import com.clipdown.downloader.model.TaskEntity
import com.clipdown.parser.model.MediaItem
import kotlinx.coroutines.delay

/**
 * 下载管理页。
 *
 * 列表数据来自任务仓储（数据库快照），实时进度来自引擎的进度流：
 * 前者保证刷新/重启后状态一致，后者保证进度条流畅，两者叠加显示。
 *
 * 图集分组：同一来源帖子（sourceUrl 相同）聚合为一张"整包"组卡，避免按下载顺序平铺产生大量冗余条目。
 * 版式与首页共用同一套卡片/胶囊/间距令牌。
 */
@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val tasks by DownloadController.repository().tasks.collectAsStateWithLifecycle()
    val progressMap = remember { mutableStateMapOf<String, ProgressEvent>() }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

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

    val rows = remember(tasks) { buildRows(tasks) }
    val activeCount = remember(tasks) { tasks.count { it.status.isActive } }
    val doneCount = remember(tasks) { tasks.count { it.status == DownloadStatus.COMPLETED } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppTheme.spacing.screen),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)
    ) {
        item {
            PageHeader(
                title = "下载",
                subtitle = when {
                    rows.isEmpty() -> "还没有任务"
                    activeCount > 0 -> "${tasks.size} 个任务 · 进行中 $activeCount"
                    else -> "${tasks.size} 个任务 · 已完成 $doneCount"
                }
            )
        }

        if (rows.isEmpty()) {
            item {
                AppCard {
                    EmptyState(
                        title = "还没有下载任务",
                        desc = "复制一条链接，点悬浮气泡即可识别下载",
                        icon = Icons.Default.Download
                    )
                }
            }
            return@LazyColumn
        }

        items(rows, key = { it.key }) { row ->
            when (row) {
                is DownloadRow.Single -> TaskRow(
                    task = row.task,
                    progress = progressMap[row.task.id],
                    onPause = { DownloadController.pause(row.task.id) },
                    onResume = { DownloadController.resume(row.task.id) },
                    onDelete = { DownloadController.repository().delete(row.task.id) },
                    onOpen = { openTask(context, row.task) },
                    onOpenSource = { openSource(context, row.task) }
                )
                is DownloadRow.Album -> AlbumGroupCard(
                    tasks = row.tasks,
                    expanded = expandedGroups[row.key] == true,
                    progressMap = progressMap,
                    onToggleExpand = { expandedGroups[row.key] = !(expandedGroups[row.key] ?: false) },
                    onOpen = {
                        openTask(
                            context,
                            row.tasks.firstOrNull { it.status == DownloadStatus.COMPLETED } ?: row.tasks.first()
                        )
                    },
                    onOpenSource = { openSource(context, row.tasks.first()) },
                    onOpenTask = { openTask(context, it) },
                    onPause = { DownloadController.pause(it) },
                    onResume = { DownloadController.resume(it) },
                    onDelete = { DownloadController.repository().delete(it) }
                )
            }
        }
    }
}

// ---------- 分组 ----------

/** 下载页列表行：单资源任务独立成行；同来源帖子的多条记录聚合为图集组 */
private sealed class DownloadRow {
    abstract val key: String

    data class Single(val task: TaskEntity) : DownloadRow() {
        override val key: String get() = task.id
    }

    /** tasks 按创建时间升序（图 1 → 图 N） */
    data class Album(val tasks: List<TaskEntity>) : DownloadRow() {
        override val key: String get() = tasks.first().sourceUrl ?: tasks.first().id
    }
}

private fun buildRows(tasks: List<TaskEntity>): List<DownloadRow> {
    val entries = mutableListOf<Pair<Long, DownloadRow>>()
    tasks.filter { it.sourceUrl == null }.forEach {
        entries += it.updatedAt to DownloadRow.Single(it)
    }
    tasks.filter { it.sourceUrl != null }.groupBy { it.sourceUrl!! }.forEach { (_, list) ->
        entries += list.maxOf { it.updatedAt } to DownloadRow.Album(list.sortedBy { it.createdAt })
    }
    return entries.sortedByDescending { it.first }.map { it.second }
}

private fun openTask(context: android.content.Context, task: TaskEntity) {
    val uri = task.localUri ?: return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse(uri), task.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}

private fun openSource(context: android.content.Context, task: TaskEntity) {
    val url = task.sourceUrl ?: return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}

// ---------- 单任务卡片 ----------

@Composable
private fun TaskRow(
    task: TaskEntity,
    progress: ProgressEvent?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onOpenSource: () -> Unit = {},
    compact: Boolean = false
) {
    val clickable = task.status == DownloadStatus.COMPLETED && task.localUri != null
    AppCard(padding = if (compact) AppTheme.spacing.md else AppTheme.spacing.lg) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                VSpace(2.dp)
                Text(
                    statusText(task, progress) + " · " + fmtTime(task.updatedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(AppTheme.spacing.sm))
            StatusPill(statusLabel(task.status), statusTone(task.status))
        }

        if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PENDING) {
            VSpace(AppTheme.spacing.md)
            ProgressBar(task.progress)
        }

        VSpace(AppTheme.spacing.xs)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen, enabled = clickable) {
                Text("打开", style = MaterialTheme.typography.labelLarge)
            }
            if (task.sourceUrl != null) {
                TextButton(onClick = onOpenSource) {
                    Text("来源", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.weight(1f))
            when (task.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.MERGING ->
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = "暂停", modifier = Modifier.size(20.dp))
                    }

                DownloadStatus.PAUSED, DownloadStatus.FAILED ->
                    IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "继续", modifier = Modifier.size(20.dp))
                    }

                else -> Unit
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ---------- 图集组卡片 ----------

@Composable
private fun AlbumGroupCard(
    tasks: List<TaskEntity>,
    expanded: Boolean,
    progressMap: Map<String, ProgressEvent>,
    onToggleExpand: () -> Unit,
    onOpen: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenTask: (TaskEntity) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val done = tasks.count { it.status == DownloadStatus.COMPLETED }
    val failed = tasks.count { it.status == DownloadStatus.FAILED }
    val active = tasks.count { it.status.isActive }
    val totalBytes = tasks.sumOf { if (it.status == DownloadStatus.COMPLETED) it.totalBytes else 0L }
    val allDone = done == tasks.size

    AppCard {
        // 整包头：标题 + 状态胶囊 + 明细开关
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tasks.first().title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                VSpace(2.dp)
                Text(
                    buildString {
                        append("图集 ${tasks.size} 张")
                        when {
                            allDone -> append(" · 全部完成 · ${MediaItem.formatSize(totalBytes)}")
                            failed > 0 -> append(" · 完成 $done / 失败 $failed")
                            active > 0 -> append(" · 下载中 $done/${tasks.size}")
                        }
                        append(" · ${fmtTime(tasks.maxOf { it.updatedAt })}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(AppTheme.spacing.sm))
            when {
                allDone -> StatusPill("已完成", Tone.Success)
                failed > 0 -> StatusPill("$failed 失败", Tone.Danger)
                else -> StatusPill("下载中", Tone.Brand)
            }
        }

        if (active > 0) {
            VSpace(AppTheme.spacing.md)
            ProgressBar(done.toFloat() / tasks.size)
        }

        VSpace(AppTheme.spacing.xs)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen, enabled = done > 0) {
                Text(if (tasks.size > 1) "打开（第 1 张）" else "打开", style = MaterialTheme.typography.labelLarge)
            }
            if (tasks.first().sourceUrl != null) {
                TextButton(onClick = onOpenSource) {
                    Text("来源", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleExpand) {
                Text(if (expanded) "收起" else "明细", style = MaterialTheme.typography.labelLarge)
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                VSpace(AppTheme.spacing.xs)
                tasks.forEach { task ->
                    TaskRow(
                        task = task,
                        progress = progressMap[task.id],
                        onPause = { onPause(task.id) },
                        onResume = { onResume(task.id) },
                        onDelete = { onDelete(task.id) },
                        onOpen = { onOpenTask(task) },
                        onOpenSource = {},
                        compact = true
                    )
                }
            }
        }
    }
}

/** 统一进度条：6dp 高、全圆角、品牌色 */
@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(AppTheme.radius.pill))
    ) {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.PENDING -> "等待中"
    DownloadStatus.DOWNLOADING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.MERGING -> "处理中"
    DownloadStatus.COMPLETED -> "已完成"
    DownloadStatus.FAILED -> "失败"
    DownloadStatus.CANCELED -> "已取消"
}

private fun statusTone(status: DownloadStatus): Tone = when (status) {
    DownloadStatus.PENDING, DownloadStatus.CANCELED -> Tone.Neutral
    DownloadStatus.DOWNLOADING, DownloadStatus.MERGING -> Tone.Brand
    DownloadStatus.PAUSED -> Tone.Warning
    DownloadStatus.COMPLETED -> Tone.Success
    DownloadStatus.FAILED -> Tone.Danger
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
        else -> statusLabel(task.status)
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024.0
    return if (kb >= 1024) String.format("%.1f MB/s", kb / 1024) else String.format("%.0f KB/s", kb)
}

/** 下载记录时间显示：任务完成/更新时间 */
private val timeFmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())

private fun fmtTime(millis: Long): String = timeFmt.format(java.util.Date(millis))

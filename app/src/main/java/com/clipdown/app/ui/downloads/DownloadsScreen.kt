package com.clipdown.app.ui.downloads

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.clipdown.app.ui.components.AppCard
import com.clipdown.app.ui.components.EmptyState
import com.clipdown.app.ui.components.MediaViewerDialog
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
import java.io.File

/**
 * 下载管理页。
 *
 * 列表数据来自任务仓储（数据库快照），实时进度来自引擎的进度流：
 * 前者保证刷新/重启后状态一致，后者保证进度条流畅，两者叠加显示。
 *
 * 图集分组：同一来源帖子（sourceUrl 相同）聚合为一张"整包"组卡，避免按下载顺序平铺产生大量冗余条目。
 * 现已支持：
 * 1. 列表直观媒体缩略图预览（图片/视频带播放徽标）。
 * 2. 点击条目即呼出内置全屏图片/视频浏览器（多图可滑动、手势双击捏合缩放、视频带控制条循环播放）。
 * 3. 删除文件联动：点击删除带确认弹窗，明确提示同时删除本地文件与记录。
 */
@Composable
fun DownloadsScreen() {
    val context = LocalContext.current
    val tasks by DownloadController.repository().tasks.collectAsStateWithLifecycle()
    val progressMap = remember { mutableStateMapOf<String, ProgressEvent>() }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    // 内置全屏媒体浏览器状态
    var previewTasks by remember { mutableStateOf<List<TaskEntity>?>(null) }
    var previewInitialIndex by remember { mutableIntStateOf(0) }

    // 删除确认弹窗状态（单条与整组）
    var taskPendingDelete by remember { mutableStateOf<TaskEntity?>(null) }
    var albumPendingDelete by remember { mutableStateOf<List<TaskEntity>?>(null) }

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
                    onDeleteRequest = { taskPendingDelete = row.task },
                    onPreview = {
                        previewTasks = listOf(row.task)
                        previewInitialIndex = 0
                    },
                    onOpenSource = { openSource(context, row.task) }
                )
                is DownloadRow.Album -> AlbumGroupCard(
                    tasks = row.tasks,
                    expanded = expandedGroups[row.key] == true,
                    progressMap = progressMap,
                    onToggleExpand = { expandedGroups[row.key] = !(expandedGroups[row.key] ?: false) },
                    onPreviewAlbum = { initialTask ->
                        val completed = row.tasks.filter { it.status == DownloadStatus.COMPLETED }
                        if (completed.isNotEmpty()) {
                            previewTasks = completed
                            previewInitialIndex = if (initialTask != null) {
                                completed.indexOf(initialTask).coerceAtLeast(0)
                            } else 0
                        }
                    },
                    onOpenSource = { openSource(context, row.tasks.first()) },
                    onPause = { DownloadController.pause(it) },
                    onResume = { DownloadController.resume(it) },
                    onDeleteTaskRequest = { task -> taskPendingDelete = task },
                    onDeleteAllRequest = { albumPendingDelete = row.tasks }
                )
            }
        }
    }

    // 单个任务删除确认弹窗
    taskPendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { taskPendingDelete = null },
            title = { Text("删除文件确认", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "确定要删除「${task.title}」对应的本地媒体文件以及下载记录吗？删除后本地文件将无法恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        DownloadController.repository().delete(task.id)
                        Toast.makeText(context, "已删除本地文件与记录", Toast.LENGTH_SHORT).show()
                        taskPendingDelete = null
                    }
                ) {
                    Text("删除文件", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { taskPendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 整组任务删除确认弹窗
    albumPendingDelete?.let { tasksToDelete ->
        AlertDialog(
            onDismissRequest = { albumPendingDelete = null },
            title = { Text("批量删除文件确认", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "确定要删除该图组共 ${tasksToDelete.size} 个本地媒体文件及下载记录吗？删除后本地文件将无法恢复。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        tasksToDelete.forEach { DownloadController.repository().delete(it.id) }
                        Toast.makeText(context, "已删除全部 ${tasksToDelete.size} 个本地文件与记录", Toast.LENGTH_SHORT).show()
                        albumPendingDelete = null
                    }
                ) {
                    Text("全部删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { albumPendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 内置媒体浏览器
    previewTasks?.let { pTasks ->
        MediaViewerDialog(
            tasks = pTasks,
            initialIndex = previewInitialIndex,
            onDismiss = { previewTasks = null },
            onDeleteTask = { taskToDelete ->
                DownloadController.repository().delete(taskToDelete.id)
                val remaining = pTasks.filter { it.id != taskToDelete.id }
                if (remaining.isEmpty()) {
                    previewTasks = null
                } else {
                    previewTasks = remaining
                    previewInitialIndex = previewInitialIndex.coerceIn(0, remaining.size - 1)
                }
                Toast.makeText(context, "已删除文件及记录", Toast.LENGTH_SHORT).show()
            }
        )
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
        // 单条记录不成组：单视频/单图有来源链接时按独立任务展示，
        // 否则会把单视频误标成"图集 1 张"（用户实测反馈）
        if (list.size == 1) {
            entries += list[0].updatedAt to DownloadRow.Single(list[0])
        } else {
            entries += list.maxOf { it.updatedAt } to DownloadRow.Album(list.sortedBy { it.createdAt })
        }
    }
    return entries.sortedByDescending { it.first }.map { it.second }
}

/** 媒体类别文案：按 mimeType 判定（视频/图片），供下载页准确标注类别 */
private fun categoryLabel(task: TaskEntity): String =
    if (task.mimeType.startsWith("video/")) "视频"
    else if (task.mimeType.startsWith("audio/")) "音频"
    else "图片"

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
    onDeleteRequest: () -> Unit,
    onPreview: () -> Unit,
    onOpenSource: () -> Unit = {},
    compact: Boolean = false
) {
    val clickable = task.status == DownloadStatus.COMPLETED && (task.localUri != null || task.localPath != null)
    AppCard(padding = if (compact) AppTheme.spacing.md else AppTheme.spacing.lg) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (clickable) Modifier.clickable { onPreview() } else Modifier
        ) {
            // 左侧媒体预览缩略图
            Box(
                modifier = Modifier
                    .size(if (compact) 46.dp else 56.dp)
                    .clip(RoundedCornerShape(AppTheme.radius.thumb))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                // 兜底图标：当缩略图未就绪或加载失败时展示
                Icon(
                    imageVector = if (task.mimeType.startsWith("video/")) Icons.Default.Movie else Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(if (compact) 22.dp else 26.dp)
                )
                val thumbModel = resolveThumbModel(task)
                if (thumbModel != null) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(thumbModel)
                            .videoFrameMillis(1000)
                            .crossfade(true)
                            .build(),
                        contentDescription = task.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                if (task.mimeType.startsWith("video/")) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = "视频",
                            tint = Color.White,
                            modifier = Modifier.size(if (compact) 20.dp else 26.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(AppTheme.spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    task.title,
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                VSpace(2.dp)
                Text(
                    categoryLabel(task) + " · " + statusText(task, progress) + " · " + fmtTime(task.updatedAt),
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
            TextButton(onClick = onPreview, enabled = clickable) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("预览", style = MaterialTheme.typography.labelLarge)
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
            IconButton(onClick = onDeleteRequest) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除文件",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
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
    onPreviewAlbum: (TaskEntity?) -> Unit,
    onOpenSource: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDeleteTaskRequest: (TaskEntity) -> Unit,
    onDeleteAllRequest: () -> Unit
) {
    val done = tasks.count { it.status == DownloadStatus.COMPLETED }
    val failed = tasks.count { it.status == DownloadStatus.FAILED }
    val active = tasks.count { it.status.isActive }
    val totalBytes = tasks.sumOf { if (it.status == DownloadStatus.COMPLETED) it.totalBytes else 0L }
    val allDone = done == tasks.size
    // 组类别按实际 mimeType 统计，不再一律叫"图集"
    val videoCount = tasks.count { it.mimeType.startsWith("video/") }
    val groupLabel = when {
        videoCount == 0 -> "图集 ${tasks.size} 张"
        videoCount == tasks.size -> "视频 ${tasks.size} 个"
        else -> "视频 $videoCount + 图片 ${tasks.size - videoCount}"
    }

    AppCard {
        // 整包头：标题 + 状态胶囊 + 明细开关
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (done > 0) Modifier.clickable { onPreviewAlbum(null) } else Modifier
        ) {
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
                        append(groupLabel)
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

        // 下载中展示整体进度条
        if (active > 0) {
            VSpace(AppTheme.spacing.md)
            ProgressBar(done.toFloat() / tasks.size)
        }

        // 已完成的图片缩略图横排画廊展示，点任意一张直接唤起该图内置预览
        if (done > 0) {
            val completedTasks = remember(tasks) { tasks.filter { it.status == DownloadStatus.COMPLETED } }
            VSpace(AppTheme.spacing.sm)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                items(completedTasks, key = { it.id }) { t ->
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(AppTheme.radius.thumb))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPreviewAlbum(t) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (t.mimeType.startsWith("video/")) Icons.Default.Movie else Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(20.dp)
                        )
                        val model = resolveThumbModel(t)
                        if (model != null) {
                            val context = LocalContext.current
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(model)
                                    .videoFrameMillis(1000)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        if (t.mimeType.startsWith("video/")) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        VSpace(AppTheme.spacing.xs)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onPreviewAlbum(null) }, enabled = done > 0) {
                Icon(
                    Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (tasks.size > 1) "浏览全部 ($done)" else "预览", style = MaterialTheme.typography.labelLarge)
            }
            if (tasks.first().sourceUrl != null) {
                TextButton(onClick = onOpenSource) {
                    Text("来源", style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(onClick = onDeleteAllRequest, enabled = active == 0) {
                Text("删除全部", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
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
                        onDeleteRequest = { onDeleteTaskRequest(task) },
                        onPreview = { onPreviewAlbum(task) },
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

private fun resolveThumbModel(task: TaskEntity): Any? {
    task.localPath?.let {
        val f = File(it)
        if (f.exists() && f.length() > 0) return f
    }
    task.localUri?.let { if (it.isNotBlank()) return android.net.Uri.parse(it) }
    return task.coverUrl
}

/** 下载记录时间显示：任务完成/更新时间 */
private val timeFmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())

private fun fmtTime(millis: Long): String = timeFmt.format(java.util.Date(millis))

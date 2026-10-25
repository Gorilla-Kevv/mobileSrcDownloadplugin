package com.clipdown.app.ui.downloads

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 下载管理页（现代直角平面网格风格，全面采用四方形组件，严格杜绝圆角）。
 *
 * 核心特性：
 * 1. 严格四方形态：所有卡片、缩略图、状态标签、进度条、操作按钮及确认对话框均统一采用 [RectangleShape]。
 * 2. 真实路径可见与复制：直观展示已下载媒体文件的本地物理路径 / MediaStore 路径，并支持一键快捷复制。
 * 3. 聚合图集展示：相同来源帖子聚合为图集组，支持整体与细分项的路径审查、批量与独立删除、一键预览。
 * 4. 内置沉浸式媒体浏览器：无缝调用全屏图片/视频播放器，带循环播放与手势缩放。
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
    // 进度每 500ms 落库，这里按 1s 刷新一次列表快照
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            DownloadController.repository().refresh()
        }
    }

    val rows = remember(tasks) { buildRows(tasks) }
    val activeCount = remember(tasks) { tasks.count { it.status.isActive } }
    val doneCount = remember(tasks) { tasks.count { it.status == DownloadStatus.COMPLETED } }
    val failedCount = remember(tasks) { tasks.count { it.status == DownloadStatus.FAILED } }
    val totalBytes = remember(tasks) {
        tasks.filter { it.status == DownloadStatus.COMPLETED }.sumOf { it.totalBytes }
    }

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
                    activeCount > 0 -> "${tasks.size} 个任务 · 进行中 $activeCount · 已完成 $doneCount · 失败 $failedCount"
                    failedCount > 0 -> "${tasks.size} 个任务 · 已完成 $doneCount · 失败 $failedCount · 占用 ${MediaItem.formatSize(totalBytes)}"
                    else -> "${tasks.size} 个任务 · 全部完成 · 占用 ${MediaItem.formatSize(totalBytes)}"
                }
            )
        }

        if (rows.isEmpty()) {
            item {
                AppCard(shape = RectangleShape) {
                    EmptyState(
                        title = "还没有下载任务",
                        desc = "复制一条链接，点悬浮气泡即可识别下载",
                        icon = Icons.Default.Download,
                        shape = RectangleShape
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

    // 单个任务删除确认弹窗（四方形设计）
    taskPendingDelete?.let { task ->
        val path = task.localPath ?: task.localUri
        AlertDialog(
            shape = RectangleShape,
            onDismissRequest = { taskPendingDelete = null },
            title = { Text("删除文件确认", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Text(
                        "确定要删除「${task.title}」对应的本地媒体文件以及下载记录吗？删除后本地文件将无法恢复。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!path.isNullOrBlank()) {
                        VSpace(AppTheme.spacing.sm)
                        Text(
                            "文件路径: $path",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    shape = RectangleShape,
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
                TextButton(shape = RectangleShape, onClick = { taskPendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 整组任务删除确认弹窗（四方形设计）
    albumPendingDelete?.let { tasksToDelete ->
        AlertDialog(
            shape = RectangleShape,
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
                    shape = RectangleShape,
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
                TextButton(shape = RectangleShape, onClick = { albumPendingDelete = null }) {
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

// ---------- 分组数据结构 ----------

private sealed class DownloadRow {
    abstract val key: String

    data class Single(val task: TaskEntity) : DownloadRow() {
        override val key: String get() = task.id
    }

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
        if (list.size == 1) {
            entries += list[0].updatedAt to DownloadRow.Single(list[0])
        } else {
            entries += list.maxOf { it.updatedAt } to DownloadRow.Album(list.sortedBy { it.createdAt })
        }
    }
    return entries.sortedByDescending { it.first }.map { it.second }
}

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

// ---------- 单任务卡片（纯四方形、展示路径、支持复制） ----------

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
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val clickable = task.status == DownloadStatus.COMPLETED && (task.localUri != null || task.localPath != null)
    val thumbSize = if (compact) 48.dp else 60.dp
    val filePath = task.localPath ?: task.localUri

    AppCard(
        shape = RectangleShape,
        padding = if (compact) AppTheme.spacing.md else AppTheme.spacing.lg
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (clickable) Modifier.clickable { onPreview() } else Modifier
        ) {
            // 左侧媒体预览缩略图（纯直角四边形 + 1dp 细线描边）
            Box(
                modifier = Modifier
                    .size(thumbSize)
                    .clip(RectangleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (task.mimeType.startsWith("video/")) Icons.Default.Movie else Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(if (compact) 22.dp else 26.dp)
                )
                val thumbModel = resolveThumbModel(task)
                if (thumbModel != null) {
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
                            .background(Color.Black.copy(alpha = 0.35f)),
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
            StatusPill(
                text = statusLabel(task.status),
                tone = statusTone(task.status),
                shape = RectangleShape
            )
        }

        // 下载中展示平直四方形进度条
        if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.PENDING || task.status == DownloadStatus.MERGING) {
            VSpace(AppTheme.spacing.sm)
            ProgressBar(task.progress)
        }

        // 真实下载文件路径展示块（带文件夹图标及快捷复制）
        if (!filePath.isNullOrBlank()) {
            VSpace(AppTheme.spacing.sm)
            FilePathBlock(
                path = filePath,
                compact = compact,
                onCopy = {
                    clipboard.setText(AnnotatedString(filePath))
                    Toast.makeText(context, "已复制路径: $filePath", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // 底部平直操作工具栏
        VSpace(AppTheme.spacing.sm)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareActionButton(
                text = "预览",
                icon = Icons.Default.Visibility,
                enabled = clickable,
                onClick = onPreview
            )
            if (task.sourceUrl != null) {
                Spacer(Modifier.width(AppTheme.spacing.xs))
                SquareActionButton(
                    text = "来源",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = onOpenSource
                )
            }
            Spacer(Modifier.weight(1f))
            when (task.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.MERGING -> {
                    SquareActionButton(
                        text = "暂停",
                        icon = Icons.Default.Pause,
                        onClick = onPause
                    )
                    Spacer(Modifier.width(AppTheme.spacing.xs))
                }
                DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                    SquareActionButton(
                        text = "继续",
                        icon = Icons.Default.PlayArrow,
                        onClick = onResume
                    )
                    Spacer(Modifier.width(AppTheme.spacing.xs))
                }
                else -> Unit
            }
            SquareActionButton(
                text = "删除",
                icon = Icons.Default.Delete,
                isDanger = true,
                onClick = onDeleteRequest
            )
        }
    }
}

// ---------- 图集组卡片（纯四方形、展示目录、支持复制与展开明细） ----------

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
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val done = tasks.count { it.status == DownloadStatus.COMPLETED }
    val failed = tasks.count { it.status == DownloadStatus.FAILED }
    val active = tasks.count { it.status.isActive }
    val totalBytes = tasks.sumOf { if (it.status == DownloadStatus.COMPLETED) it.totalBytes else 0L }
    val allDone = done == tasks.size
    val videoCount = tasks.count { it.mimeType.startsWith("video/") }
    val groupLabel = when {
        videoCount == 0 -> "图集 ${tasks.size} 张"
        videoCount == tasks.size -> "视频 ${tasks.size} 个"
        else -> "视频 $videoCount + 图片 ${tasks.size - videoCount}"
    }

    // 提取公共存储目录（供图集头展示与一键复制）
    val commonDir = remember(tasks) {
        tasks.firstOrNull { it.localPath != null }?.localPath?.let {
            File(it).parent
        } ?: tasks.firstOrNull { it.localUri != null }?.localUri
    }

    AppCard(shape = RectangleShape) {
        // 整包头：标题 + 状态胶囊 + 统计
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (done > 0) Modifier.clickable { onPreviewAlbum(null) } else Modifier
        ) {
            // 图集封面（优先使用首个已完成作品，四方形 + 角标）
            val firstCoverModel = resolveThumbModel(
                tasks.firstOrNull { it.status == DownloadStatus.COMPLETED } ?: tasks.first()
            )
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RectangleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (videoCount > 0) Icons.Default.Movie else Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(26.dp)
                )
                if (firstCoverModel != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(firstCoverModel)
                            .videoFrameMillis(1000)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .clip(RectangleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        "${tasks.size}P",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
                    )
                }
            }

            Spacer(Modifier.width(AppTheme.spacing.md))

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
                allDone -> StatusPill("已完成", Tone.Success, shape = RectangleShape)
                failed > 0 -> StatusPill("$failed 失败", Tone.Danger, shape = RectangleShape)
                else -> StatusPill("下载中", Tone.Brand, shape = RectangleShape)
            }
        }

        // 下载中展示整体平直进度条
        if (active > 0) {
            VSpace(AppTheme.spacing.md)
            ProgressBar(done.toFloat() / tasks.size)
        }

        // 显示图集所在公共目录路径
        if (!commonDir.isNullOrBlank()) {
            VSpace(AppTheme.spacing.sm)
            FilePathBlock(
                path = commonDir,
                label = "存储目录",
                onCopy = {
                    clipboard.setText(AnnotatedString(commonDir))
                    Toast.makeText(context, "已复制图集目录: $commonDir", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // 已完成的图片缩略图横排画廊展示（纯直角四边形，带播放指示与边框）
        if (done > 0) {
            val completedTasks = remember(tasks) { tasks.filter { it.status == DownloadStatus.COMPLETED } }
            VSpace(AppTheme.spacing.sm)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)) {
                items(completedTasks, key = { it.id }) { t ->
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RectangleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
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
                                    .background(Color.Black.copy(alpha = 0.35f)),
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

        VSpace(AppTheme.spacing.sm)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SquareActionButton(
                text = if (tasks.size > 1) "浏览全部 ($done)" else "预览",
                icon = Icons.Default.Visibility,
                enabled = done > 0,
                onClick = { onPreviewAlbum(null) }
            )
            if (tasks.first().sourceUrl != null) {
                Spacer(Modifier.width(AppTheme.spacing.xs))
                SquareActionButton(
                    text = "来源",
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = onOpenSource
                )
            }
            Spacer(Modifier.width(AppTheme.spacing.xs))
            SquareActionButton(
                text = "全部删除",
                icon = Icons.Default.Delete,
                isDanger = true,
                enabled = active == 0,
                onClick = onDeleteAllRequest
            )
            Spacer(Modifier.weight(1f))
            SquareActionButton(
                text = if (expanded) "收起" else "明细 (${tasks.size})",
                icon = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                onClick = onToggleExpand
            )
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

// ---------- 四方形基础组件（路径展示条、直角动作按钮、平直进度条） ----------

/**
 * 四方形文件路径展示块：等宽字体、单行防溢出、一键复制到剪贴板
 */
@Composable
private fun FilePathBlock(
    path: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    compact: Boolean = false,
    onCopy: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .clickable { onCopy() },
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(if (compact) 13.dp else 15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            if (label != null) {
                Text(
                    text = "[$label]",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp)
                )
            }
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (compact) 10.sp else 11.sp,
                    lineHeight = if (compact) 13.sp else 14.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RectangleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制路径",
                        modifier = Modifier.size(11.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        "复制",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 统一直角操作按钮（四边形微带描边，悬停/按下触感利落）
 */
@Composable
private fun SquareActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    isDanger: Boolean = false,
    isPrimary: Boolean = false
) {
    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        isDanger -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        isPrimary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        isDanger -> MaterialTheme.colorScheme.error
        isPrimary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val bgColor = when {
        isPrimary && enabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        isDanger && enabled -> MaterialTheme.colorScheme.error.copy(alpha = 0.06f)
        else -> MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = modifier
            .clip(RectangleShape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RectangleShape,
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = contentColor
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = contentColor
            )
        }
    }
}

/** 统一进度条：四边平直四方形、4dp 高、品牌色 */
@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RectangleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(4.dp),
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

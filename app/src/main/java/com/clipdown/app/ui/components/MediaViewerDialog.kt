package com.clipdown.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.clipdown.downloader.model.TaskEntity
import com.clipdown.parser.model.MediaItem
import java.io.File

/**
 * 内置媒体浏览器：全屏图片查看（双击放大/手势缩放/多图滑动切换）+ 视频播放（内置播放与控制条）+ 分享与删除。
 */
@Composable
fun MediaViewerDialog(
    tasks: List<TaskEntity>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    onDeleteTask: ((TaskEntity) -> Unit)? = null
) {
    if (tasks.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val context = LocalContext.current
    val safeIndex = initialIndex.coerceIn(0, tasks.size - 1)
    val pagerState = rememberPagerState(initialPage = safeIndex, pageCount = { tasks.size })
    val currentTask = tasks.getOrNull(pagerState.currentPage) ?: tasks.first()

    var showControls by remember { mutableStateOf(true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 媒体滑动视口
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { showControls = !showControls }
                        )
                    }
            ) { page ->
                val task = tasks.getOrNull(page)
                if (task != null) {
                    if (task.mimeType.startsWith("video/")) {
                        VideoPlayerPage(
                            task = task,
                            isCurrentPage = pagerState.currentPage == page,
                            onToggleControls = { showControls = !showControls }
                        )
                    } else {
                        ImageViewerPage(
                            task = task,
                            onToggleControls = { showControls = !showControls }
                        )
                    }
                }
            }

            // 顶部操作栏
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = Color.White
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = currentTask.title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val sub = buildString {
                                if (tasks.size > 1) {
                                    append("${pagerState.currentPage + 1}/${tasks.size} · ")
                                }
                                append(if (currentTask.mimeType.startsWith("video/")) "视频" else "图片")
                                if (currentTask.totalBytes > 0) {
                                    append(" · ${MediaItem.formatSize(currentTask.totalBytes)}")
                                }
                            }
                            Text(
                                text = sub,
                                color = Color.White.copy(alpha = 0.7f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // 分享
                        IconButton(onClick = { shareTaskMedia(context, currentTask) }) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "分享",
                                tint = Color.White
                            )
                        }

                        // 用系统外部播放器/图库打开
                        IconButton(onClick = { openWithExternalApp(context, currentTask) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "外部打开",
                                tint = Color.White
                            )
                        }

                        // 删除
                        if (onDeleteTask != null) {
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除文件",
                                    tint = Color(0xFFFF5252)
                                )
                            }
                        }
                    }
                }
            }

            // 底部详情栏
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    Column {
                        Text(
                            text = currentTask.fileName,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        currentTask.localPath?.let { p ->
                            Text(
                                text = p,
                                color = Color.White.copy(alpha = 0.45f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除本地文件确认") },
            text = { Text("确定要删除「${currentTask.title}」对应的本地媒体文件和下载记录吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDeleteTask?.invoke(currentTask)
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/** 单图查看：支持双击放大缩小、捏合手势缩放、自由拖动 */
@Composable
private fun ImageViewerPage(
    task: TaskEntity,
    onToggleControls: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offset = if (scale > 1f) {
            Offset(offset.x + offsetChange.x, offset.y + offsetChange.y)
        } else {
            Offset.Zero
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .transformable(state = transformState),
        contentAlignment = Alignment.Center
    ) {
        val model = resolveMediaSource(task)
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = task.title,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                contentScale = ContentScale.Fit
            )
        } else {
            Text("本地文件不存在或已被删除", color = Color.White.copy(alpha = 0.6f))
        }
    }
}

/** 视频播放：使用 Android 原生 VideoView + MediaController，支持硬件解码、拖动进度与循环播放 */
@Composable
private fun VideoPlayerPage(
    task: TaskEntity,
    isCurrentPage: Boolean,
    onToggleControls: () -> Unit
) {
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }

    DisposableEffect(task.id) {
        onDispose {
            videoViewRef?.stopPlayback()
            videoViewRef = null
        }
    }

    LaunchedEffect(isCurrentPage) {
        val vv = videoViewRef ?: return@LaunchedEffect
        if (isCurrentPage) {
            if (isPrepared && !vv.isPlaying) {
                vv.start()
                isPlaying = true
            }
        } else {
            if (vv.isPlaying) {
                vv.pause()
                isPlaying = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleControls() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val mediaUri = resolveMediaUri(task)
        if (mediaUri != null) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoURI(mediaUri)
                        setOnPreparedListener { mp ->
                            isPrepared = true
                            mp.isLooping = true
                            if (isCurrentPage) {
                                start()
                                isPlaying = true
                            }
                        }
                        setOnCompletionListener {
                            isPlaying = false
                        }
                        videoViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (!isPrepared) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        } else {
            Text("视频文件不存在或已被删除", color = Color.White.copy(alpha = 0.6f))
        }
    }
}

/** 获取媒体的数据模型（供 Coil 读取） */
private fun resolveMediaSource(task: TaskEntity): Any? {
    task.localUri?.let { uriStr ->
        if (uriStr.isNotBlank()) return Uri.parse(uriStr)
    }
    task.localPath?.let { path ->
        val f = File(path)
        if (f.exists()) return f
    }
    return task.coverUrl
}

/** 获取媒体的播放 Uri（供 VideoView 读取） */
private fun resolveMediaUri(task: TaskEntity): Uri? {
    task.localUri?.let { uriStr ->
        if (uriStr.isNotBlank()) return Uri.parse(uriStr)
    }
    task.localPath?.let { path ->
        val f = File(path)
        if (f.exists()) return Uri.fromFile(f)
    }
    return null
}

/** 分享媒体文件到其他应用 */
private fun shareTaskMedia(context: Context, task: TaskEntity) {
    val uri = getShareableUri(context, task) ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = task.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享作品"))
    }
}

/** 使用外部系统应用打开媒体 */
private fun openWithExternalApp(context: Context, task: TaskEntity) {
    val uri = getShareableUri(context, task) ?: return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, task.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}

/** 获取可供 Intent 分享/查看的安全 Uri */
private fun getShareableUri(context: Context, task: TaskEntity): Uri? {
    task.localUri?.let { uriStr ->
        val uri = Uri.parse(uriStr)
        if (uri.scheme == "content") return uri
    }
    task.localPath?.let { path ->
        val file = File(path)
        if (file.exists()) {
            return runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull() ?: Uri.fromFile(file)
        }
    }
    return null
}

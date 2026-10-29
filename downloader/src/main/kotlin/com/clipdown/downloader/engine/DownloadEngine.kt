package com.clipdown.downloader.engine

import android.content.Context
import com.clipdown.downloader.TaskRepository
import com.clipdown.downloader.db.TaskDatabase
import com.clipdown.downloader.model.DownloadConfig
import com.clipdown.downloader.model.DownloadStatus
import com.clipdown.downloader.model.ProgressEvent
import com.clipdown.downloader.model.TaskEntity
import com.clipdown.downloader.model.TaskKind
import com.clipdown.downloader.notify.DownloadNotifier
import com.clipdown.downloader.storage.MediaStoreWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载引擎（自研）。
 *
 * 结构：一个无界 Channel 作为任务队列，N 个 worker 协程消费，
 * 并发度由 [DownloadConfig.maxConcurrent] 决定；每个任务一个 Job，可随时取消。
 *
 * 三类任务：
 * - SINGLE：直连单文件，支持 Range 断点续传；
 * - HLS：m3u8 分片并发下载 + AES-128 解密 + 拼接；
 * - DASH：视频轨与音频轨分别下载后合并为 MP4。
 *
 * 失败策略：可重试异常按指数退避重试 [DownloadConfig.maxRetry] 次，
 * 取消与手动暂停不触发重试。落库前进行文件物理完整性强校验（杜绝截断黑图）。
 */
class DownloadEngine(
    private val context: Context,
    private val db: TaskDatabase,
    private val repository: TaskRepository,
    private val scope: CoroutineScope,
    config: DownloadConfig
) {

    private val notifier = DownloadNotifier(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val httpFile = HttpFileDownloader(client, config.connectTimeoutMs, config.readTimeoutMs)
    private val m3u8 = M3u8Downloader(client, config.segmentConcurrency)

    private val queue = Channel<String>(Channel.UNLIMITED)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val speedTrackers = ConcurrentHashMap<String, SpeedTracker>()

    private val sessionCompleted = AtomicInteger(0)
    private val sessionFailed = AtomicInteger(0)

    private val _progress = MutableSharedFlow<ProgressEvent>(extraBufferCapacity = 64)
    val progress: SharedFlow<ProgressEvent> = _progress.asSharedFlow()

    @Volatile
    var config: DownloadConfig = config
        private set

    @Volatile
    var listener: EngineListener? = null

    interface EngineListener {
        fun onTaskUpdated(task: TaskEntity)
        fun onForegroundStateChanged(activeCount: Int)
    }

    private val workDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    fun start() {
        repeat(config.maxConcurrent.coerceAtLeast(1)) {
            scope.launch {
                for (taskId in queue) {
                    if (!isActive) break
                    runTask(taskId)
                }
            }
        }
    }

    fun updateConfig(newConfig: DownloadConfig) {
        config = newConfig
    }

    fun activeCount(): Int {
        val dbCount = runCatching { db.activeCount() }.getOrDefault(0)
        // 自愈防护：若内存中当前无作业且任务队列已空，但数据库由于历史崩溃/异常仍残留非终态任务，
        // 自动触发 resetOrphans 重置孤儿任务，彻底防止 activeCount 永久卡死大于 0
        if (jobs.isEmpty() && dbCount > 0) {
            db.resetOrphans()
            repository.refresh()
            return 0
        }
        return dbCount
    }

    fun foregroundNotification() = notifier.foregroundNotification(
        activeCount = activeCount(),
        completedCount = sessionCompleted.get(),
        failedCount = sessionFailed.get()
    )

    fun refreshForegroundNotification() {
        val active = activeCount()
        val completed = sessionCompleted.get()
        val failed = sessionFailed.get()
        notifier.updateForeground(active, completed, failed)
        listener?.onForegroundStateChanged(active)
    }

    fun enqueue(task: TaskEntity) {
        if (activeCount() == 0) {
            sessionCompleted.set(0)
            sessionFailed.set(0)
        }
        db.insert(task)
        repository.refresh()
        refreshForegroundNotification()
        queue.trySend(task.id)
    }

    fun pause(taskId: String) {
        jobs[taskId]?.cancel()
        jobs.remove(taskId)
        db.updateStatus(taskId, DownloadStatus.PAUSED)
        notifier.cancel(taskId)
        repository.refresh()
        refreshForegroundNotification()
    }

    fun resume(taskId: String) {
        val t = db.get(taskId) ?: return
        if (t.status.isActive) return
        if (activeCount() == 0) {
            sessionCompleted.set(0)
            sessionFailed.set(0)
        }
        db.updateStatus(taskId, DownloadStatus.PENDING)
        repository.refresh()
        refreshForegroundNotification()
        queue.trySend(taskId)
    }

    fun retry(taskId: String) = resume(taskId)

    fun cancel(taskId: String) {
        jobs[taskId]?.cancel()
        jobs.remove(taskId)
        db.updateStatus(taskId, DownloadStatus.CANCELED)
        notifier.cancel(taskId)
        repository.refresh()
        refreshForegroundNotification()
    }

    fun cancelAll() {
        jobs.keys.toList().forEach { cancel(it) }
        refreshForegroundNotification()
    }

    private suspend fun runTask(taskId: String) {
        var attempt = 0
        while (attempt <= config.maxRetry) {
            val task = db.get(taskId) ?: return
            if (task.status == DownloadStatus.CANCELED || task.status == DownloadStatus.PAUSED) return

            db.updateStatus(taskId, DownloadStatus.DOWNLOADING)
            repository.refresh()
            refreshForegroundNotification()

            // 关键：任务启动进入 DOWNLOADING 瞬间，立即发出初始进度通知（0%），消除 500ms 盲区
            notifier.notifyProgress(
                task,
                ProgressEvent(taskId, task.downloadedBytes, task.totalBytes, 0, DownloadStatus.DOWNLOADING)
            )

            val state = HttpFileDownloader.State(
                downloaded = task.downloadedBytes,
                total = task.totalBytes
            )
            val job = scope.launch { reportLoop(taskId, state) }
            jobs[taskId] = job

            val result = runCatching { execute(task, state) }

            job.cancelAndJoin()
            jobs.remove(taskId)
            speedTrackers.remove(taskId)

            val failure = result.exceptionOrNull()
            when {
                result.isSuccess -> {
                    sessionCompleted.incrementAndGet()
                    db.get(taskId)?.let { notifier.notifyFinished(it) }
                    repository.refresh()
                    refreshForegroundNotification()
                    listener?.onTaskUpdated(db.get(taskId) ?: return)
                    return
                }
                failure is DownloadCanceledException -> {
                    val current = db.get(taskId)
                    if (current?.status != DownloadStatus.PAUSED && current?.status != DownloadStatus.CANCELED) {
                        db.updateStatus(taskId, DownloadStatus.PAUSED)
                    }
                    repository.refresh()
                    refreshForegroundNotification()
                    return
                }
                else -> {
                    attempt++
                    db.incrementRetry(taskId)
                    // 当多次断点续传重试仍不通过时，清理可能因中断损坏或错位的半截文件，在末次重试时从 0 字节全新拉取
                    val targetFile = File(workDir, task.fileName)
                    if (attempt >= 3 && targetFile.exists()) {
                        targetFile.delete()
                    }
                    if (attempt > config.maxRetry) {
                        // 诊断日志：终态失败此前完全静默（阶段 17 排查 403 时 logcat 无任何线索）
                        android.util.Log.w(
                            "DownloadEngine",
                            "终态失败 ${task.fileName}: ${failure?.message} | ${task.url}"
                        )
                        db.updateStatus(taskId, DownloadStatus.FAILED, failure?.message ?: "下载失败")
                        _progress.tryEmit(
                            ProgressEvent(taskId, task.downloadedBytes, task.totalBytes, 0, DownloadStatus.FAILED)
                        )
                        sessionFailed.incrementAndGet()
                        db.get(taskId)?.let { notifier.notifyFinished(it) }
                        notifier.cancel(taskId)
                        repository.refresh()
                        refreshForegroundNotification()
                        return
                    }
                    delay(config.retryBackoffMs * attempt)
                }
            }
        }
    }

    private suspend fun execute(task: TaskEntity, state: HttpFileDownloader.State) {
        val tmpDir = File(workDir, task.id).apply { mkdirs() }
        val target = File(workDir, task.fileName)

        when (task.kind) {
            TaskKind.SINGLE -> {
                httpFile.download(task.url, target, task.headers, state)
                MediaIntegrityValidator.validate(target, state.total, task.mimeType)
            }

            TaskKind.HLS -> {
                val ok = m3u8.download(task.url, task.headers, tmpDir, target, state)
                if (!ok) throw DownloadHttpException(-1, "分片下载未完成")
                MediaIntegrityValidator.validate(target, state.total, task.mimeType)
            }

            TaskKind.DASH -> {
                val videoFile = File(tmpDir, "video.m4s")
                val audioFile = File(tmpDir, "audio.m4s")
                httpFile.download(task.url, videoFile, task.headers, state)
                if (task.audioUrl != null) {
                    val audioState = HttpFileDownloader.State()
                    httpFile.download(task.audioUrl, audioFile, task.audioHeaders, audioState)
                }
                val merged = MediaRemuxer.mergeTracks(videoFile, audioFile, target)
                if (!merged) {
                    MediaRemuxer.concatSegments(listOf(videoFile, audioFile), target)
                }
                state.downloaded = target.length()
                state.total = target.length()
                MediaIntegrityValidator.validate(target, state.total, task.mimeType)
            }
        }

        db.updateStatus(task.id, DownloadStatus.MERGING)
        _progress.tryEmit(
            ProgressEvent(task.id, state.downloaded, state.total, 0, DownloadStatus.MERGING)
        )
        db.get(task.id)?.let { notifier.notifyMerging(it) }

        val uri = if (config.saveToAlbum) {
            MediaStoreWriter.save(context, target, task.fileName, task.mimeType)
        } else null

        db.markCompleted(
            id = task.id,
            localPath = target.absolutePath,
            localUri = uri?.toString(),
            mimeType = task.mimeType,
            totalBytes = target.length()
        )
        // 终态事件：悬浮窗等订阅方依赖它把 UI 切到"下载完成"
        _progress.tryEmit(
            ProgressEvent(task.id, target.length(), target.length(), 0, DownloadStatus.COMPLETED)
        )
        tmpDir.deleteRecursively()
    }

    /** 采样：更新数据库、进度流与通知 */
    private suspend fun reportLoop(taskId: String, state: HttpFileDownloader.State) {
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            val task = db.get(taskId) ?: break
            // 严格守卫：若任务已非活动或已进入合并处理阶段，立即退出循环，绝不覆写状态
            if (!task.status.isActive || task.status == DownloadStatus.MERGING) break

            val now = System.currentTimeMillis()
            val downloaded = state.downloaded
            val dt = (now - lastTime).coerceAtLeast(1)
            val speed = ((downloaded - lastBytes) * 1000 / dt).coerceAtLeast(0)
            lastBytes = downloaded
            lastTime = now

            if (!currentCoroutineContext().isActive) break

            db.updateProgress(taskId, downloaded, state.total, DownloadStatus.DOWNLOADING)
            val event = ProgressEvent(taskId, downloaded, state.total, speed, DownloadStatus.DOWNLOADING)
            _progress.tryEmit(event)
            notifier.notifyProgress(task, event)

            delay(500)
        }
    }

    private class SpeedTracker(var lastBytes: Long, var lastTime: Long)

    companion object {
        fun tempFileFor(context: Context, taskId: String): File =
            File(File(context.filesDir, "downloads"), taskId)
    }
}

package com.clipdown.downloader

import android.content.Context
import com.clipdown.downloader.db.TaskDatabase
import com.clipdown.downloader.engine.DownloadEngine
import com.clipdown.downloader.model.DownloadConfig
import com.clipdown.downloader.model.TaskEntity
import com.clipdown.downloader.model.TaskKind
import com.clipdown.parser.core.UrlUtil
import com.clipdown.parser.model.MediaItem
import com.clipdown.parser.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

/**
 * 下载器对外的唯一入口。
 *
 * 上层（UI / 悬浮窗）只需要认识这个门面：
 * - [install] 在 Application 中初始化一次；
 * - [enqueue] 把解析产物直接变成下载任务；
 * - [observe] 通过 TaskRepository 的 Flow 观察列表与状态。
 */
object DownloadController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var engine: DownloadEngine? = null

    @Volatile
    private var database: TaskDatabase? = null

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context, config: DownloadConfig = DownloadConfig()) {
        if (engine != null) {
            engine?.updateConfig(config)
            return
        }
        val ctx = context.applicationContext
        appContext = ctx
        val db = TaskDatabase(ctx)
        db.resetOrphans()
        database = db
        val repo = TaskRepository.install(db)
        val e = DownloadEngine(ctx, db, repo, scope, config)
        engine = e
        e.start()
    }

    fun engine(): DownloadEngine = engine
        ?: error("DownloadController 未初始化，请先调用 install(context)")

    fun repository(): TaskRepository = TaskRepository.get()

    fun updateConfig(config: DownloadConfig) {
        engine?.updateConfig(config)
    }

    /**
     * 把解析出的媒体项加入下载队列。
     *
     * @param platform 来源平台，仅用于分组展示
     * @param title 作品标题，用于生成文件名
     * @param sourceUrl 来源帖子链接（图集分组 + 下载页"查看来源"）
     * @return 任务 ID
     */
    fun enqueue(item: MediaItem, platform: Platform, title: String?, sourceUrl: String? = null): String {
        // 入口兜底（修复 9 的第二道防线）：不管解析哪条路径漏了转义，入库 URL 必须干净——
        // 页面转义形态随版本变化，曾出现 video_versions 单层 \/ 残留导致 CDN 403
        val cleanUrl = item.url.replace("\\/", "/")
        val kind = when {
            item.isPlaylist || item.container == "m3u8" ||
                cleanUrl.substringBefore('?').endsWith(".m3u8") -> TaskKind.HLS
            !item.audioUrl.isNullOrBlank() -> TaskKind.DASH
            else -> TaskKind.SINGLE
        }
        val ext = when (kind) {
            TaskKind.HLS -> "mp4"
            TaskKind.DASH -> "mp4"
            TaskKind.SINGLE -> (item.container ?: guessExt(cleanUrl)).lowercase()
        }
        val base = UrlUtil.sanitizeFileName(title ?: item.fileNameHint ?: "clipdown")
        val fileName = "$base-${
            UUID.randomUUID().toString().take(4)
        }.$ext"

        val mime = when {
            item.mimeType != null -> item.mimeType!!
            kind == TaskKind.HLS || kind == TaskKind.DASH -> "video/mp4"
            ext in listOf("jpg", "jpeg", "png", "webp", "gif") -> "image/$ext"
            else -> "video/mp4"
        }

        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            title = title ?: base,
            url = cleanUrl,
            audioUrl = item.audioUrl,
            headers = item.headers,
            audioHeaders = item.audioHeaders ?: item.headers,
            fileName = fileName,
            mimeType = mime,
            container = ext,
            kind = kind,
            platformId = platform.id,
            coverUrl = null,
            sourceUrl = sourceUrl
        )
        engine().enqueue(task)
        return task.id
    }

    fun enqueueAll(items: List<MediaItem>, platform: Platform, title: String?, sourceUrl: String? = null): List<String> =
        items.map { enqueue(it, platform, title, sourceUrl) }

    fun pause(taskId: String) = engine().pause(taskId)
    fun resume(taskId: String) = engine().resume(taskId)
    fun cancel(taskId: String) = engine().cancel(taskId)

    private fun guessExt(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "mp4").takeIf { it.length in 2..4 } ?: "mp4"
}

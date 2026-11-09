package com.clipdown.downloader

import android.content.Context
import android.os.Build
import com.clipdown.downloader.db.TaskDatabase
import com.clipdown.downloader.engine.DownloadEngine
import com.clipdown.downloader.model.DownloadConfig
import com.clipdown.downloader.model.DownloadStatus
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
        val repo = TaskRepository.install(db, ctx)
        val e = DownloadEngine(ctx, db, repo, scope, config)
        engine = e
        e.start()
    }

    fun engine(): DownloadEngine = engine
        ?: error("DownloadController 未初始化，请先调用 install(context)")

    fun repository(): TaskRepository = TaskRepository.get()

    fun database(): TaskDatabase = database
        ?: error("DownloadController 未初始化，请先调用 install(context)")

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
    fun enqueue(
        item: MediaItem,
        platform: Platform,
        title: String?,
        sourceUrl: String? = null,
        coverUrl: String? = null
    ): String {
        // 入口兜底（修复 9 的第二道防线）：不管解析哪条路径漏了转义，入库 URL 必须干净——
        // 页面转义形态随版本变化，曾出现 video_versions 单层 \/ 残留导致 CDN 403
        val cleanUrl = sanitizeTaskUrl(item.url)

        // 防重复下载（阶段 79）：若同一个媒体已经在队列进行中（或 15 秒内刚完成），直接复用现有任务。
        // 比对必须用 [UrlUtil.dedupKey] 而不是原始 URL：小红书/X/IG 的媒体直链带轮换签名，
        // 两次解析同一视频会拿到不同 query，裸串比对会漏判，导致「载 2 次同一个视频」。
        // 有 sourceUrl 时再要求来源帖子一致，避免不同帖子共用同一 CDN 资源时误合并。
        val urlKey = UrlUtil.dedupKey(cleanUrl)
        val existingActive = repository().tasks.value.firstOrNull { t ->
            UrlUtil.dedupKey(t.url) == urlKey &&
                (sourceUrl == null || t.sourceUrl == null || t.sourceUrl == sourceUrl) &&
                (t.status.isActive ||
                    (t.status == DownloadStatus.COMPLETED && System.currentTimeMillis() - t.updatedAt < RECENTLY_COMPLETED_MS))
        }
        if (existingActive != null) {
            ensureServiceStarted()
            return existingActive.id
        }

        // 只记录**来源帖子 URL**进入「已解析链接」表。
        // 旧实现把媒体直链（cleanUrl）也写进去，会让这张表被每个媒体文件永久污染一行，
        // 且媒体 URL 与页面 URL 不同域，对"已解析"提示毫无用处。
        sourceUrl?.takeIf { it.isNotBlank() }?.let { database?.markLinkSeen(it) }
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
            coverUrl = coverUrl ?: if (mime.startsWith("image/")) cleanUrl else null,
            sourceUrl = sourceUrl
        )
        engine().enqueue(task)
        ensureServiceStarted()
        return task.id
    }

    private fun ensureServiceStarted() {
        val ctx = appContext ?: return
        runCatching {
            val intent = DownloadService.intent(ctx, DownloadService.ACTION_RESUME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    fun enqueueAll(items: List<MediaItem>, platform: Platform, title: String?, sourceUrl: String? = null, coverUrl: String? = null): List<String> =
        items.map { enqueue(it, platform, title, sourceUrl, coverUrl) }

    fun pause(taskId: String) = engine().pause(taskId)
    fun resume(taskId: String) = engine().resume(taskId)
    fun cancel(taskId: String) = engine().cancel(taskId)

    /**
     * 入口 URL 兜底清洗：IG 页面数据的 JSON-in-JS 转义残留曾导致 CDN 403（修复 9 第二道防线）。
     * 单独暴露以便单测锁定：无论解析侧漏了哪条路径，入库 URL 都不允许出现字面 `\/`。
     */
    internal fun sanitizeTaskUrl(url: String): String = url.replace("\\/", "/")

    internal fun guessExt(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "mp4").takeIf { it.length in 2..4 } ?: "mp4"

    /**
     * 「刚完成」窗口：判重时把这么短时间内完成的任务视为同一请求的重入，
     * 复用其任务 ID 而不是再下一份。
     *
     * 语义与 `app/clip/DedupeWindows.AUTO_DOWNLOAD_MS` 对应，但 `:downloader`
     * 不能反向依赖 `:app`，故在此独立声明；改一处务必同步另一处。
     */
    private const val RECENTLY_COMPLETED_MS = 15_000L
}

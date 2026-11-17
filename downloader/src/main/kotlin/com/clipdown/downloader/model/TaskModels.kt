package com.clipdown.downloader.model

/** 任务状态机：PENDING → DOWNLOADING → MERGING → COMPLETED，可侧向迁移到 PAUSED / FAILED / CANCELED */
enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    MERGING,
    COMPLETED,
    FAILED,
    CANCELED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELED
    val isActive: Boolean get() = this == PENDING || this == DOWNLOADING || this == MERGING
}

/** 任务类型：决定下载器走直连还是分片合并流程 */
enum class TaskKind { SINGLE, HLS, DASH }

/** 下载任务实体，与数据库表一一对应 */
data class TaskEntity(
    val id: String,
    val title: String,
    val url: String,
    val audioUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val audioHeaders: Map<String, String> = emptyMap(),
    val fileName: String,
    val mimeType: String = "video/mp4",
    val container: String = "mp4",
    val kind: TaskKind = TaskKind.SINGLE,
    val platformId: String = "generic",
    val coverUrl: String? = null,
    /** 来源帖子链接（图集分组键 + 下载页"查看来源"入口）；旧任务/无来源为 null */
    val sourceUrl: String? = null,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val localUri: String? = null,
    val localPath: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
) {
    val progress: Float
        get() = when {
            status == DownloadStatus.COMPLETED -> 1f
            totalBytes <= 0L -> 0f
            else -> (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }
}

/** 实时进度事件，UI 与通知共用 */
data class ProgressEvent(
    val taskId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val status: DownloadStatus
)

/** 下载器的对外配置 */
data class DownloadConfig(
    val maxConcurrent: Int = 3,
    val wifiOnly: Boolean = false,
    val saveToAlbum: Boolean = true,
    val segmentConcurrency: Int = 3,
    val connectTimeoutMs: Long = 15_000,
    val readTimeoutMs: Long = 30_000,
    val maxRetry: Int = 3,
    /** 分片下载失败后的重试退避基数 */
    val retryBackoffMs: Long = 1_500L,
    /**
     * 自定义保存子目录（阶段 84）。
     *
     * 语义：**相对公共媒体根目录**的路径，例如 `ClipDown` / `ClipDown/视频` / `MyVault/ClipDown`。
     * - 留空或 null → 使用默认 `ClipDown`
     * - 只允许相对路径（自动剥掉开头的 `/` 与盘符），防止越权写到别处
     * - 视频仍落在 Movies 下、图片落 Pictures、音频落 Music，只是再往下多一层自定义目录
     */
    val saveSubDir: String? = null,
    /**
     * 是否在入库相册成功后**删除私有目录副本**（阶段 84 消除双份存储）。
     *
     * 必须为 true（默认）：相册里的那份就是唯一实体，私有目录只作为下载中的临时中转。
     * 只有 `saveToAlbum = false` 时私有副本才是唯一实体、不能删。
     */
    val deletePrivateCopyAfterSave: Boolean = true
) {
    /** 规整后的子目录：剥掉绝对路径前缀与多余斜杠，为空时回落到默认值 */
    fun normalizedSubDir(default: String = "ClipDown"): String {
        val raw = saveSubDir?.trim().orEmpty()
        if (raw.isEmpty()) return default
        return raw
            .replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." && !it.contains(':') }
            .joinToString("/")
            .ifBlank { default }
    }
}

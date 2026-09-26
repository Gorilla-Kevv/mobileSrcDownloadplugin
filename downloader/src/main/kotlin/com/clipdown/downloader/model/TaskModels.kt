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
    val retryBackoffMs: Long = 1_500L
)

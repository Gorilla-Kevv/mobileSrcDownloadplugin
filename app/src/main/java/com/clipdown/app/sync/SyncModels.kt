package com.clipdown.app.sync

import kotlinx.serialization.Serializable

@Serializable
data class SyncStatusResponse(
    val deviceName: String,
    val appVersion: String,
    val port: Int,
    val paired: Boolean,
    val taskCount: Int,
    val loggedInPlatforms: List<String>
)

@Serializable
data class SyncCookieRequest(
    val platform: String,
    val cookie: String
)

@Serializable
data class SyncCookieBatchRequest(
    val cookies: Map<String, String>
)

@Serializable
data class SyncEnqueueRequest(
    val url: String
)

@Serializable
data class SyncTaskItem(
    val id: String,
    val title: String,
    val url: String,
    val fileName: String,
    val mimeType: String = "video/mp4",
    val container: String = "mp4",
    val kind: String = "SINGLE",
    val platformId: String = "generic",
    val coverUrl: String? = null,
    val sourceUrl: String? = null,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: String = "COMPLETED",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

@Serializable
data class SyncTasksResponse(
    val total: Int,
    val tasks: List<SyncTaskItem>
)

@Serializable
data class SyncTasksBatchRequest(
    val tasks: List<SyncTaskItem>
)

@Serializable
data class SyncBatchEnqueueItem(
    val url: String,
    val title: String? = null,
    val coverUrl: String? = null
)

@Serializable
data class SyncBatchEnqueueRequest(
    val urls: List<String> = emptyList(),
    val items: List<SyncBatchEnqueueItem> = emptyList()
)


@Serializable
data class SyncApiResponse(
    val success: Boolean,
    val message: String,
    val data: String? = null
)

package com.clipdown.downloader.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.clipdown.downloader.model.DownloadStatus
import com.clipdown.downloader.model.TaskEntity
import com.clipdown.downloader.model.TaskKind
import com.clipdown.parser.core.UrlUtil
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 任务持久化。
 *
 * 这里手写 SQLite 而不是引入 Room：下载任务的表结构极其稳定，
 * 手写可以避免注解处理器带来的构建开销，同时便于精确控制"进度高频写入"这类场景
 * （进度更新走单独的 UPDATE 语句，不做整行替换，减少 IO 放大）。
 */
class TaskDatabase(context: Context) :
    SQLiteOpenHelper(context, "clipdown_tasks.db", null, DB_VERSION) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tasks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                audio_url TEXT,
                headers TEXT,
                audio_headers TEXT,
                file_name TEXT NOT NULL,
                mime_type TEXT,
                container TEXT,
                kind TEXT NOT NULL,
                platform_id TEXT,
                cover_url TEXT,
                source_url TEXT,
                total_bytes INTEGER DEFAULT 0,
                downloaded_bytes INTEGER DEFAULT 0,
                status TEXT NOT NULL,
                local_uri TEXT,
                local_path TEXT,
                error_message TEXT,
                created_at INTEGER,
                updated_at INTEGER,
                retry_count INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_created ON tasks(created_at DESC)")

        // 阶段 79：已解析链接长期持久化数据库表（杜绝内存限制或粗暴减半丢弃）
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS seen_links (
                url TEXT PRIMARY KEY,
                normalized_url TEXT,
                created_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_seen_normalized ON seen_links(normalized_url)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2：来源帖子链接（图集分组 + 下载页"查看来源"）
            db.execSQL("ALTER TABLE tasks ADD COLUMN source_url TEXT")
        }
        if (oldVersion < 3) {
            // v3：长期保存已解析链接表与归一化索引
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS seen_links (
                    url TEXT PRIMARY KEY,
                    normalized_url TEXT,
                    created_at INTEGER
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_seen_normalized ON seen_links(normalized_url)")
        }
    }

    fun insert(task: TaskEntity) {
        writableDatabase.insertWithOnConflict(TABLE, null, task.toCv(json), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateProgress(id: String, downloaded: Long, total: Long, status: DownloadStatus) {
        val cv = ContentValues().apply {
            put("downloaded_bytes", downloaded)
            if (total > 0) put("total_bytes", total)
            put("status", status.name)
            put("updated_at", System.currentTimeMillis())
        }
        // 防御性守卫：只在任务仍处于未终态（PENDING / DOWNLOADING）时才允许更新进度与状态，
        // 杜绝任何已进入 MERGING / COMPLETED / FAILED / CANCELED 的任务被异步进度采样覆写回 DOWNLOADING
        writableDatabase.update(
            TABLE,
            cv,
            "id = ? AND status IN (?, ?)",
            arrayOf(id, DownloadStatus.PENDING.name, DownloadStatus.DOWNLOADING.name)
        )
    }

    fun updateStatus(id: String, status: DownloadStatus, error: String? = null) {
        val cv = ContentValues().apply {
            put("status", status.name)
            put("updated_at", System.currentTimeMillis())
            if (error != null) put("error_message", error)
        }
        writableDatabase.update(TABLE, cv, "id = ?", arrayOf(id))
    }

    fun markCompleted(
        id: String,
        localPath: String,
        localUri: String?,
        mimeType: String,
        totalBytes: Long
    ) {
        val cv = ContentValues().apply {
            put("status", DownloadStatus.COMPLETED.name)
            put("local_path", localPath)
            put("local_uri", localUri)
            put("mime_type", mimeType)
            put("total_bytes", totalBytes)
            put("downloaded_bytes", totalBytes)
            put("updated_at", System.currentTimeMillis())
            putNull("error_message")
        }
        writableDatabase.update(TABLE, cv, "id = ?", arrayOf(id))
    }

    fun incrementRetry(id: String) {
        writableDatabase.execSQL("UPDATE tasks SET retry_count = retry_count + 1 WHERE id = ?", arrayOf(id))
    }

    fun get(id: String): TaskEntity? =
        readableDatabase.query(TABLE, null, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) c.toTask(json) else null
        }

    fun all(): List<TaskEntity> =
        readableDatabase.query(TABLE, null, null, null, null, null, "created_at DESC").use { c ->
            buildList {
                while (c.moveToNext()) add(c.toTask(json))
            }
        }

    /** 获取当前处于非终态（等待中、下载中、合并处理中）的任务总数 */
    fun activeCount(): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tasks WHERE status IN (?,?,?)",
            arrayOf(DownloadStatus.PENDING.name, DownloadStatus.DOWNLOADING.name, DownloadStatus.MERGING.name)
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    data class TaskCounts(val active: Int, val completed: Int, val failed: Int)

    fun counts(): TaskCounts {
        var active = 0
        var completed = 0
        var failed = 0
        readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM tasks GROUP BY status",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val statusStr = c.getString(0)
                val count = c.getInt(1)
                val status = runCatching { DownloadStatus.valueOf(statusStr) }.getOrNull()
                when {
                    status?.isActive == true -> active += count
                    status == DownloadStatus.COMPLETED -> completed += count
                    status == DownloadStatus.FAILED -> failed += count
                }
            }
        }
        return TaskCounts(active, completed, failed)
    }

    /** 启动时调用：把上次进程被杀时残留的进行中任务置为失败，避免 UI 显示"永远在进行" */
    fun resetOrphans() {
        val cv = ContentValues().apply {
            put("status", DownloadStatus.FAILED.name)
            put("error_message", "进程重启，任务已中断")
        }
        writableDatabase.update(
            TABLE, cv, "status IN (?,?,?)",
            arrayOf(DownloadStatus.PENDING.name, DownloadStatus.DOWNLOADING.name, DownloadStatus.MERGING.name)
        )
    }

    fun delete(id: String) {
        writableDatabase.delete(TABLE, "id = ?", arrayOf(id))
    }

    fun clearFinished() {
        writableDatabase.delete(
            TABLE, "status IN (?,?,?)",
            arrayOf(DownloadStatus.COMPLETED.name, DownloadStatus.FAILED.name, DownloadStatus.CANCELED.name)
        )
    }

    /**
     * 长期记录一条已解析/已识别的链接。
     *
     * `url` 列保留原始形态便于人眼核对，`normalized_url` 列存 [UrlUtil.dedupKey]
     * ——后者会剥掉 `xsec_token` 等随分享轮换的鉴权参数并收敛到笔记 ID，
     * 这是「几天前解析过的链接再解析一遍仍能弹提示」的关键。
     */
    fun markLinkSeen(url: String, canonicalUrl: String? = null) {
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        val cv1 = ContentValues().apply {
            put("url", url)
            put("normalized_url", UrlUtil.dedupKey(url))
            put("created_at", now)
        }
        writableDatabase.insertWithOnConflict(SEEN_TABLE, null, cv1, SQLiteDatabase.CONFLICT_REPLACE)
        if (!canonicalUrl.isNullOrBlank() && canonicalUrl != url) {
            val cv2 = ContentValues().apply {
                put("url", canonicalUrl)
                put("normalized_url", UrlUtil.dedupKey(canonicalUrl))
                put("created_at", now)
            }
            writableDatabase.insertWithOnConflict(SEEN_TABLE, null, cv2, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * 查询指定链接是否在历史中解析或下载过。
     *
     * 比对一律走 [UrlUtil.dedupKey]：原始 URL 里的 `xsec_token` 每次分享都会变，
     * 旧实现用 [UrlUtil.normalize]（保留 token）导致同一条小红书笔记永远匹配不上。
     */
    fun isLinkSeen(url: String, canonicalUrl: String? = null): Boolean {
        if (url.isBlank()) return false
        val key = UrlUtil.dedupKey(url)
        val key2 = canonicalUrl?.takeIf { it.isNotBlank() }?.let { UrlUtil.dedupKey(it) }

        // 1. 检查 seen_links 持久化历史表（normalized_url 列存的就是 dedupKey）
        //    额外带上 legacyNormalized：v3 早期行写的是「保留 xsec_token 的 normalize 结果」，
        //    不兼容这一形态会让升级前的历史一次性全部失效。
        val legacy1 = UrlUtil.normalize(url)
        val legacy2 = canonicalUrl?.takeIf { it.isNotBlank() }?.let { UrlUtil.normalize(it) }
        val sql = "SELECT 1 FROM $SEEN_TABLE WHERE url = ? OR normalized_url = ? OR normalized_url = ?" +
            if (key2 != null) " OR url = ? OR normalized_url = ? OR normalized_url = ?" else ""
        val args = if (key2 != null) {
            arrayOf(url, key, legacy1, canonicalUrl ?: "", key2, legacy2 ?: "")
        } else {
            arrayOf(url, key, legacy1)
        }
        runCatching {
            readableDatabase.rawQuery(sql, args).use { cursor ->
                if (cursor.moveToFirst()) return true
            }
        }

        // 2. 检查 tasks 表（历史任务中的 source_url 或 url）
        val taskSql = "SELECT 1 FROM $TABLE WHERE url = ? OR source_url = ?" +
            if (canonicalUrl != null) " OR url = ? OR source_url = ?" else ""
        val taskArgs = if (canonicalUrl != null) {
            arrayOf(url, url, canonicalUrl, canonicalUrl)
        } else {
            arrayOf(url, url)
        }
        runCatching {
            readableDatabase.rawQuery(taskSql, taskArgs).use { cursor ->
                if (cursor.moveToFirst()) return true
            }
        }

        return false
    }

    fun getAllSeenUrls(): Set<String> {
        val set = mutableSetOf<String>()
        runCatching {
            readableDatabase.rawQuery("SELECT url FROM $SEEN_TABLE", null).use { cursor ->
                while (cursor.moveToNext()) {
                    set.add(cursor.getString(0))
                }
            }
        }
        return set
    }

    companion object {
        private const val TABLE = "tasks"
        private const val SEEN_TABLE = "seen_links"
        private const val DB_VERSION = 3
    }
}

private fun TaskEntity.toCv(json: Json): ContentValues = ContentValues().apply {
    put("id", id)
    put("title", title)
    put("url", url)
    put("audio_url", audioUrl)
    put("headers", json.encodeToString(headers))
    put("audio_headers", json.encodeToString(audioHeaders))
    put("file_name", fileName)
    put("mime_type", mimeType)
    put("container", container)
    put("kind", kind.name)
    put("platform_id", platformId)
    put("cover_url", coverUrl)
    put("source_url", sourceUrl)
    put("total_bytes", totalBytes)
    put("downloaded_bytes", downloadedBytes)
    put("status", status.name)
    put("local_uri", localUri)
    put("local_path", localPath)
    put("error_message", errorMessage)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
    put("retry_count", retryCount)
}

private fun android.database.Cursor.toTask(json: Json): TaskEntity {
    fun str(col: String): String? = getString(getColumnIndexOrThrow(col))
    fun long(col: String): Long = getLong(getColumnIndexOrThrow(col))
    fun int(col: String): Int = getInt(getColumnIndexOrThrow(col))
    return TaskEntity(
        id = str("id") ?: "",
        title = str("title") ?: "",
        url = str("url") ?: "",
        audioUrl = str("audio_url"),
        headers = runCatching { json.decodeFromString<Map<String, String>>(str("headers") ?: "{}") }.getOrDefault(emptyMap()),
        audioHeaders = runCatching {
            json.decodeFromString<Map<String, String>>(str("audio_headers") ?: "{}")
        }.getOrDefault(emptyMap()),
        fileName = str("file_name") ?: "clipdown",
        mimeType = str("mime_type") ?: "video/mp4",
        container = str("container") ?: "mp4",
        kind = runCatching { TaskKind.valueOf(str("kind") ?: "SINGLE") }.getOrDefault(TaskKind.SINGLE),
        platformId = str("platform_id") ?: "generic",
        coverUrl = str("cover_url"),
        sourceUrl = str("source_url"),
        totalBytes = long("total_bytes"),
        downloadedBytes = long("downloaded_bytes"),
        status = runCatching { DownloadStatus.valueOf(str("status") ?: "PENDING") }.getOrDefault(DownloadStatus.PENDING),
        localUri = str("local_uri"),
        localPath = str("local_path"),
        errorMessage = str("error_message"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
        retryCount = int("retry_count")
    )
}

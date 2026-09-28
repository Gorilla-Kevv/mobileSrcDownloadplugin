package com.clipdown.downloader.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.clipdown.downloader.model.DownloadStatus
import com.clipdown.downloader.model.TaskEntity
import com.clipdown.downloader.model.TaskKind
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2：来源帖子链接（图集分组 + 下载页"查看来源"）
            db.execSQL("ALTER TABLE tasks ADD COLUMN source_url TEXT")
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
        writableDatabase.update(TABLE, cv, "id = ?", arrayOf(id))
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

    companion object {
        private const val TABLE = "tasks"
        private const val DB_VERSION = 2
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

package com.clipdown.downloader

import android.content.Context
import android.net.Uri
import com.clipdown.downloader.db.TaskDatabase
import com.clipdown.downloader.model.TaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 任务仓储。
 *
 * 内存持有任务列表只读流，以 SQLite 为准。
 * UI 观察 [tasks]，变更时通过仓储写库并拉取最新快照发射到流中，
 * 避免状态流与数据库产生竞态漂移。
 */
class TaskRepository internal constructor(private val db: TaskDatabase) {

    /** 删除记录时联动删除真实文件（MediaStore 条目 / 私有目录文件） */
    private var appContext: Context? = null

    private val _tasks = MutableStateFlow<List<TaskEntity>>(emptyList())
    val tasks: StateFlow<List<TaskEntity>> = _tasks.asStateFlow()

    private val _activeIds = MutableStateFlow<Set<String>>(emptySet())
    val activeIds: StateFlow<Set<String>> = _activeIds.asStateFlow()

    fun refresh() {
        val list = runCatching { db.all() }.getOrDefault(emptyList())
        _tasks.value = list
        _activeIds.value = list.filter { it.status.isActive }.map { it.id }.toSet()
    }

    fun get(id: String): TaskEntity? = runCatching { db.get(id) }.getOrNull()

    /** 删除记录并联动删除真实落盘文件（用户预期：删记录=文件也没了） */
    fun delete(id: String) {
        get(id)?.let { deleteTaskFile(it) }
        db.delete(id)
        refresh()
    }

    private fun deleteTaskFile(task: TaskEntity) {
        val ctx = appContext ?: return
        task.localUri?.let { uriStr ->
            runCatching {
                val uri = Uri.parse(uriStr)
                when {
                    uri.scheme == "content" -> ctx.contentResolver.delete(uri, null, null)
                    uri.scheme == "file" -> uri.path?.let { File(it).delete() }
                    uriStr.startsWith("/") -> File(uriStr).delete()
                    else -> ctx.contentResolver.delete(uri, null, null)
                }
            }.onFailure { ctx.logDelete(uriStr, it) }
        }
        task.localPath?.let { p ->
            runCatching {
                val f = File(p)
                if (f.exists()) f.delete()
            }
        }
    }

    private fun Context.logDelete(uri: String, t: Throwable) =
        android.util.Log.w("TaskRepository", "删除媒体文件失败（记录仍会删除）: $uri", t)

    fun clearFinished() {
        db.all().filter { it.status.isTerminal }.forEach { deleteTaskFile(it) }
        db.clearFinished()
        refresh()
    }

    /** 跨端同步保存历史任务元数据记录（仅同步记录，不包含落盘文件） */
    fun saveRecord(task: TaskEntity) {
        db.insert(task)
        refresh()
    }

    /** 跨端批量同步保存任务元数据记录 */
    fun saveRecords(list: List<TaskEntity>) {
        if (list.isEmpty()) return
        list.forEach { db.insert(it) }
        refresh()
    }

    companion object {
        @Volatile
        private var instance: TaskRepository? = null

        fun install(db: TaskDatabase, appContext: Context): TaskRepository {
            val repo = TaskRepository(db).also { it.appContext = appContext.applicationContext }
            instance = repo
            repo.refresh()
            return repo
        }

        fun get(): TaskRepository = instance
            ?: error("TaskRepository 未初始化，请先调用 install(db, context)")
    }
}

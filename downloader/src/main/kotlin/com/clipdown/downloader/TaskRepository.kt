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
 * 数据库是唯一数据源，内存中的 StateFlow 只是它的只读快照：
 * 任何写操作后调用 [refresh] 重新读取，保证进程内所有页面看到一致的状态。
 * 这样即便服务被系统杀掉后重启，UI 也能立刻恢复出完整列表。
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
        task.localUri?.let { uri ->
            runCatching { ctx.contentResolver.delete(Uri.parse(uri), null, null) }
                .onFailure { ctx.logDelete(uri, it) }
        }
        task.localPath?.let { p ->
            runCatching { File(p).delete() }
        }
    }

    private fun Context.logDelete(uri: String, t: Throwable) =
        android.util.Log.w("TaskRepository", "删除媒体文件失败（记录仍会删除）: $uri", t)

    fun clearFinished() {
        db.all().filter { it.status.isTerminal }.forEach { deleteTaskFile(it) }
        db.clearFinished()
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
            ?: error("TaskRepository 未安装，请先调用 DownloadController.install(context)")
    }
}

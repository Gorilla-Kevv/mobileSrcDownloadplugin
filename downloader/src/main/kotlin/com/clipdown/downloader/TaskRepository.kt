package com.clipdown.downloader

import com.clipdown.downloader.db.TaskDatabase
import com.clipdown.downloader.model.TaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 任务仓储。
 *
 * 数据库是唯一数据源，内存中的 StateFlow 只是它的只读快照：
 * 任何写操作后调用 [refresh] 重新读取，保证进程内所有页面看到一致的状态。
 * 这样即便服务被系统杀掉后重启，UI 也能立刻恢复出完整列表。
 */
class TaskRepository internal constructor(private val db: TaskDatabase) {

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

    fun delete(id: String) {
        db.delete(id)
        refresh()
    }

    fun clearFinished() {
        db.clearFinished()
        refresh()
    }

    companion object {
        @Volatile
        private var instance: TaskRepository? = null

        fun install(db: TaskDatabase): TaskRepository {
            val repo = TaskRepository(db)
            instance = repo
            repo.refresh()
            return repo
        }

        fun get(): TaskRepository = instance
            ?: error("TaskRepository 未安装，请先调用 DownloadController.install(context)")
    }
}

package com.clipdown.downloader

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.clipdown.downloader.engine.DownloadEngine
import com.clipdown.downloader.model.TaskEntity
import com.clipdown.downloader.notify.DownloadNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 下载宿主服务。
 *
 * 下载引擎本身跑在 Application 级协程里，这个服务只负责两件事：
 * 1. 以前台服务身份常驻，避免下载在后台被系统限制；
 * 2. 承接通知栏上的暂停 / 取消等动作。
 */
class DownloadService : Service(), DownloadEngine.EngineListener {

    private var idleJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            DownloadController.engine().listener = this
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        idleJob?.cancel()
        serviceScope.cancel()
        runCatching {
            if (DownloadController.engine().listener === this) {
                DownloadController.engine().listener = null
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
        when (intent?.action) {
            ACTION_PAUSE -> taskId?.let { DownloadController.pause(it) }
            ACTION_RESUME -> taskId?.let { DownloadController.resume(it) }
            ACTION_CANCEL -> taskId?.let { DownloadController.cancel(it) }
            ACTION_STOP -> {
                DownloadController.engine().cancelAll()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> Unit
        }

        startForegroundCompat()
        val active = runCatching { DownloadController.engine().activeCount() }.getOrDefault(0)
        onForegroundStateChanged(active)
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = DownloadController.engine().foregroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotifier.FOREGROUND_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(DownloadNotifier.FOREGROUND_ID, notification)
        }
    }

    private fun detachForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onTaskUpdated(task: TaskEntity) {
        // no-op
    }

    override fun onForegroundStateChanged(activeCount: Int) {
        if (activeCount == 0) {
            detachForegroundCompat()
            idleJob?.cancel()
            idleJob = serviceScope.launch {
                delay(6000)
                val currentActive = runCatching { DownloadController.engine().activeCount() }.getOrDefault(0)
                if (currentActive == 0) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        } else {
            idleJob?.cancel()
            idleJob = null
            startForegroundCompat()
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.clipdown.downloader.action.PAUSE"
        const val ACTION_RESUME = "com.clipdown.downloader.action.RESUME"
        const val ACTION_CANCEL = "com.clipdown.downloader.action.CANCEL"
        const val ACTION_STOP = "com.clipdown.downloader.action.STOP"
        const val EXTRA_TASK_ID = "extra_task_id"

        fun intent(context: android.content.Context, action: String, taskId: String? = null): Intent =
            Intent(context, DownloadService::class.java).apply {
                this.action = action
                taskId?.let { putExtra(EXTRA_TASK_ID, it) }
            }
    }
}

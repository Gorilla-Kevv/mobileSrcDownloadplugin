package com.clipdown.downloader.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.clipdown.downloader.R
import com.clipdown.downloader.model.DownloadStatus
import com.clipdown.downloader.model.ProgressEvent
import com.clipdown.downloader.model.TaskEntity

/**
 * 下载通知封装。
 *
 * 前台服务的常驻通知与每个任务的进度通知共用一个 channel，
 * 进度通知用同一 notificationId，避免刷屏。
 */
class DownloadNotifier(private val context: Context) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            listOf(
                NotificationChannel(CHANNEL_DOWNLOAD, "下载任务", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示下载进度与结果"
                    setSound(null, null)
                },
                NotificationChannel(CHANNEL_RESULT, "下载完成", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "下载完成或失败提醒"
                }
            ).forEach { nm.createNotificationChannel(it) }
        }
    }

    /** 前台服务常驻通知 */
    fun foregroundNotification(activeCount: Int) =
        NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("剪存下载服务运行中")
            .setContentText(if (activeCount > 0) "正在进行 $activeCount 个下载任务" else "等待下载任务")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun notifyProgress(task: TaskEntity, event: ProgressEvent) {
        val percent = if (event.totalBytes > 0) (event.downloadedBytes * 100 / event.totalBytes) else 0
        val speed = formatSpeed(event.speedBytesPerSec)
        val n = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.title)
            .setContentText("$percent% · $speed")
            .setProgress(100, percent.toInt().coerceIn(0, 100), event.totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching { nm.notify(task.id.hashCode(), n) }
    }

    fun notifyFinished(task: TaskEntity) {
        val success = task.status == DownloadStatus.COMPLETED
        val n = NotificationCompat.Builder(context, if (success) CHANNEL_RESULT else CHANNEL_DOWNLOAD)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(task.title)
            .setContentText(if (success) "下载完成" else (task.errorMessage ?: "下载失败"))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(task.id.hashCode(), n) }
    }

    fun cancel(taskId: String) {
        runCatching { nm.cancel(taskId.hashCode()) }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "--"
        val kb = bytesPerSec / 1024.0
        return if (kb >= 1024) String.format("%.1f MB/s", kb / 1024) else String.format("%.0f KB/s", kb)
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "clipdown_download"
        const val CHANNEL_RESULT = "clipdown_result"
        const val FOREGROUND_ID = 9001
    }
}

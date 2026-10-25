package com.clipdown.downloader.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
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

    /**
     * 前台服务常驻通知。
     *
     * 满足三段式状态展示需求：同时显示正在下载数、已完成数与失败数。
     * 下载进行中为常驻通知（ongoing=true），全部完成时切为可滑除终态通知（ongoing=false），
     * 避免下载完成后仍卡在"正在进行 n 个任务"。
     */
    fun foregroundNotification(activeCount: Int, completedCount: Int = 0, failedCount: Int = 0): Notification {
        val title = formatForegroundTitle(activeCount, completedCount, failedCount)
        val content = formatForegroundContent(activeCount, completedCount, failedCount)
        val intent = getAppLaunchPendingIntent()
        val isOngoing = activeCount > 0

        val icon = when {
            activeCount > 0 -> android.R.drawable.stat_sys_download
            failedCount > 0 && completedCount == 0 -> android.R.drawable.stat_notify_error
            else -> android.R.drawable.stat_sys_download_done
        }

        return NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(isOngoing)
            .setAutoCancel(!isOngoing)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (intent != null) setContentIntent(intent)
            }
            .build()
    }

    /** 实时刷新前台服务常驻通知 */
    fun updateForeground(activeCount: Int, completedCount: Int = 0, failedCount: Int = 0) {
        runCatching {
            nm.notify(FOREGROUND_ID, foregroundNotification(activeCount, completedCount, failedCount))
        }
    }

    private fun getAppLaunchPendingIntent(): PendingIntent? = runCatching {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getActivity(context, 0, launchIntent, flags)
    }.getOrNull()

    fun notifyProgress(task: TaskEntity, event: ProgressEvent) {
        val percent = if (event.totalBytes > 0) (event.downloadedBytes * 100 / event.totalBytes) else 0
        val speed = formatSpeed(event.speedBytesPerSec)
        val intent = getAppLaunchPendingIntent()
        val n = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.title)
            .setContentText("$percent% · $speed")
            .setProgress(100, percent.toInt().coerceIn(0, 100), event.totalBytes <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (intent != null) setContentIntent(intent)
            }
            .build()
        runCatching { nm.notify(task.id.hashCode(), n) }
    }

    fun notifyFinished(task: TaskEntity) {
        val success = task.status == DownloadStatus.COMPLETED
        val intent = getAppLaunchPendingIntent()
        val n = NotificationCompat.Builder(context, if (success) CHANNEL_RESULT else CHANNEL_DOWNLOAD)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(task.title)
            .setContentText(if (success) "下载完成" else (task.errorMessage ?: "下载失败"))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (intent != null) setContentIntent(intent)
            }
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

        fun formatForegroundTitle(activeCount: Int, completedCount: Int, failedCount: Int): String = when {
            activeCount > 0 -> "剪存下载服务运行中"
            completedCount > 0 && failedCount == 0 -> "全部下载完成"
            completedCount > 0 || failedCount > 0 -> "下载任务已结束"
            else -> "剪存下载服务运行中"
        }

        fun formatForegroundContent(activeCount: Int, completedCount: Int, failedCount: Int): String =
            "正在下载 $activeCount · 已完成 $completedCount · 失败 $failedCount"
    }
}

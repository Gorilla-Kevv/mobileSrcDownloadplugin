package com.clipdown.downloader.notify

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证三段式前台常驻通知标题与正文格式化逻辑。
 */
class DownloadNotifierTest {

    @Test
    fun formatForegroundContent_displaysAllThreeSegmentsSimultaneously() {
        assertEquals(
            "正在下载 1 · 已完成 0 · 失败 0",
            DownloadNotifier.formatForegroundContent(activeCount = 1, completedCount = 0, failedCount = 0)
        )
        assertEquals(
            "正在下载 3 · 已完成 2 · 失败 1",
            DownloadNotifier.formatForegroundContent(activeCount = 3, completedCount = 2, failedCount = 1)
        )
        assertEquals(
            "正在下载 0 · 已完成 5 · 失败 0",
            DownloadNotifier.formatForegroundContent(activeCount = 0, completedCount = 5, failedCount = 0)
        )
        assertEquals(
            "正在下载 0 · 已完成 4 · 失败 1",
            DownloadNotifier.formatForegroundContent(activeCount = 0, completedCount = 4, failedCount = 1)
        )
        assertEquals(
            "正在下载 0 · 已完成 0 · 失败 0",
            DownloadNotifier.formatForegroundContent(activeCount = 0, completedCount = 0, failedCount = 0)
        )
    }

    @Test
    fun formatForegroundTitle_transitionsFromRunningToFinished() {
        // 进行中状态
        assertEquals(
            "剪存下载服务运行中",
            DownloadNotifier.formatForegroundTitle(activeCount = 2, completedCount = 1, failedCount = 0)
        )
        // 全部成功完成
        assertEquals(
            "全部下载完成",
            DownloadNotifier.formatForegroundTitle(activeCount = 0, completedCount = 3, failedCount = 0)
        )
        // 存在失败任务完成
        assertEquals(
            "下载任务已结束",
            DownloadNotifier.formatForegroundTitle(activeCount = 0, completedCount = 2, failedCount = 1)
        )
        // 纯失败完成
        assertEquals(
            "下载任务已结束",
            DownloadNotifier.formatForegroundTitle(activeCount = 0, completedCount = 0, failedCount = 1)
        )
        // 空闲状态
        assertEquals(
            "剪存下载服务运行中",
            DownloadNotifier.formatForegroundTitle(activeCount = 0, completedCount = 0, failedCount = 0)
        )
    }
}

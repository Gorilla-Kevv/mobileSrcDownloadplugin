package com.clipdown.downloader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskModelsTest {

    @Test
    fun terminalStatus() {
        assertTrue(DownloadStatus.COMPLETED.isTerminal)
        assertTrue(DownloadStatus.FAILED.isTerminal)
        assertTrue(DownloadStatus.CANCELED.isTerminal)
        assertFalse(DownloadStatus.DOWNLOADING.isTerminal)
        assertFalse(DownloadStatus.PAUSED.isTerminal)
        assertFalse(DownloadStatus.MERGING.isTerminal)
    }

    @Test
    fun activeStatus() {
        assertTrue(DownloadStatus.PENDING.isActive)
        assertTrue(DownloadStatus.DOWNLOADING.isActive)
        assertTrue(DownloadStatus.MERGING.isActive)
        assertFalse(DownloadStatus.PAUSED.isActive)
        assertFalse(DownloadStatus.COMPLETED.isActive)
    }

    @Test
    fun progress_completeIsAlwaysOne() {
        val t = TaskEntity(
            id = "1", title = "t", url = "u", fileName = "f",
            totalBytes = 0L, downloadedBytes = 0L, status = DownloadStatus.COMPLETED
        )
        assertEquals(1f, t.progress, 0f)
    }

    @Test
    fun progress_unknownTotalIsZero() {
        val t = TaskEntity(
            id = "1", title = "t", url = "u", fileName = "f",
            totalBytes = 0L, downloadedBytes = 123L
        )
        assertEquals(0f, t.progress, 0f)
    }

    @Test
    fun progress_clampedToUnit() {
        val half = TaskEntity(id = "1", title = "t", url = "u", fileName = "f", totalBytes = 100L, downloadedBytes = 25L)
        assertEquals(0.25f, half.progress, 0f)

        val overflow = half.copy(downloadedBytes = 999L)
        assertEquals(1f, overflow.progress, 0f)
    }

    @Test
    fun configDefaults() {
        val c = DownloadConfig()
        assertEquals(3, c.maxConcurrent)
        assertEquals(3, c.segmentConcurrency)
        assertEquals(3, c.maxRetry)
        assertEquals(1_500L, c.retryBackoffMs)
        assertFalse(c.wifiOnly)
        assertTrue(c.saveToAlbum)
    }
}

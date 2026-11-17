package com.clipdown.downloader

import com.clipdown.downloader.model.DownloadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「自定义保存路径」的规整规则（阶段 84）。
 *
 * 用户可能填 `/sdcard/DCIM`、`..\..\etc`、`C:\ Windows` 这类危险或无效值，
 * 规整后必须只剩安全的相对目录段，否则会写到公共媒体根之外。
 */
class DownloadConfigSubDirTest {

    @Test
    fun `空值回落到默认目录`() {
        assertEquals("ClipDown", DownloadConfig(saveSubDir = null).normalizedSubDir())
        assertEquals("ClipDown", DownloadConfig(saveSubDir = "").normalizedSubDir())
        assertEquals("ClipDown", DownloadConfig(saveSubDir = "   ").normalizedSubDir())
    }

    @Test
    fun `普通子目录原样保留`() {
        assertEquals("ClipDown", DownloadConfig(saveSubDir = "ClipDown").normalizedSubDir())
        assertEquals("ClipDown/视频", DownloadConfig(saveSubDir = "ClipDown/视频").normalizedSubDir())
    }

    @Test
    fun `反斜杠归一为正斜杠`() {
        assertEquals("ClipDown/Videos", DownloadConfig(saveSubDir = "ClipDown\\Videos").normalizedSubDir())
    }

    @Test
    fun `绝对路径前缀被剥掉`() {
        assertEquals("sdcard/DCIM", DownloadConfig(saveSubDir = "/sdcard/DCIM").normalizedSubDir())
        assertEquals("storage/emulated/0/X", DownloadConfig(saveSubDir = "//storage//emulated//0//X").normalizedSubDir())
    }

    /** 路径穿越必须被丢弃，否则可写到公共媒体根之外 */
    @Test
    fun `路径穿越段被丢弃`() {
        assertEquals("etc", DownloadConfig(saveSubDir = "../../etc").normalizedSubDir())
        assertEquals("ClipDown", DownloadConfig(saveSubDir = "../..").normalizedSubDir())
        assertEquals("a/b", DownloadConfig(saveSubDir = "a/./b").normalizedSubDir())
    }

    /** 盘符段会被丢弃，防止把 Windows 风格路径带进来 */
    @Test
    fun `盘符段被丢弃`() {
        assertEquals("Windows", DownloadConfig(saveSubDir = "C:/Windows").normalizedSubDir())
    }

    @Test
    fun `默认行为是消除私有副本`() {
        assertTrue(DownloadConfig().deletePrivateCopyAfterSave)
        assertTrue(DownloadConfig().saveToAlbum)
        assertFalse(DownloadConfig(saveToAlbum = false).deletePrivateCopyAfterSave.not())
    }
}

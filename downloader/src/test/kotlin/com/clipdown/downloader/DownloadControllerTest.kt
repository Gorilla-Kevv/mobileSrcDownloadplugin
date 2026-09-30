package com.clipdown.downloader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 只覆盖门面的纯字符串判定——不触达 engine（需 install(context)）。
 */
class DownloadControllerTest {

    @Test
    fun sanitizeTaskUrl_stripsJsonEscapedSlashes() {
        assertEquals(
            "https://scontent.cdn.com/v/t1.mp4?oe=ABC",
            DownloadController.sanitizeTaskUrl("https:\\/\\/scontent.cdn.com\\/v\\/t1.mp4?oe=ABC")
        )
    }

    @Test
    fun sanitizeTaskUrl_singleLayerEscapingAlsoCleaned() {
        assertEquals("https://cdn/a.mp4", DownloadController.sanitizeTaskUrl("https:\\/\\/cdn\\/a.mp4"))
        assertEquals("https://cdn/a.mp4", DownloadController.sanitizeTaskUrl("https://cdn/a.mp4"))
    }

    @Test
    fun guessExt_extensionFromPathIgnoringQuery() {
        assertEquals("mp4", DownloadController.guessExt("https://cdn/a.mp4?sig=1&oe=2"))
        assertEquals("jpg", DownloadController.guessExt("https://cdn/pic.jpg?w=100"))
        assertEquals("webp", DownloadController.guessExt("https://cdn/pic.webp"))
    }

    @Test
    fun guessExt_fallbacksToMp4() {
        assertEquals("mp4", DownloadController.guessExt("https://cdn/stream"))
        assertEquals("mp4", DownloadController.guessExt("https://cdn/a.toolongext"))
        assertEquals("mp4", DownloadController.guessExt("https://cdn/a.m"))
    }
}

package com.clipdown.downloader.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaIntegrityValidatorTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("cd-media-integrity-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `empty file throws exception`() {
        val file = File(tempDir, "empty.jpg").apply { createNewFile() }
        try {
            MediaIntegrityValidator.validate(file, mimeType = "image/jpeg")
            fail("Expected DownloadHttpException for empty file")
        } catch (e: DownloadHttpException) {
            assertTrue(e.message?.contains("为空") == true)
        }
    }

    @Test
    fun `incomplete length throws exception`() {
        val file = File(tempDir, "truncated.bin").apply { writeBytes(ByteArray(100)) }
        try {
            MediaIntegrityValidator.validate(file, expectedLength = 500L)
            fail("Expected DownloadHttpException for incomplete length")
        } catch (e: DownloadHttpException) {
            assertTrue(e.message?.contains("未接收完整") == true)
        }
    }

    @Test
    fun `valid jpeg passes validation`() {
        // JPEG: SOI (FF D8) ... entropy data ... EOI (FF D9)
        val validJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0x12, 0x34, 0x56, 0x78,
            0xFF.toByte(), 0xD9.toByte()
        )
        val file = File(tempDir, "valid.jpg").apply { writeBytes(validJpeg) }
        MediaIntegrityValidator.validate(file, mimeType = "image/jpeg")
    }

    @Test
    fun `valid jpeg with trailing padding bytes passes validation`() {
        val jpegWithPadding = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0x12, 0x34, 0x56,
            0xFF.toByte(), 0xD9.toByte(),
            0x00, 0x00, 0x0A
        )
        val file = File(tempDir, "valid_pad.jpeg").apply { writeBytes(jpegWithPadding) }
        MediaIntegrityValidator.validate(file, mimeType = "image/jpeg")
    }

    @Test
    fun `truncated jpeg without EOI marker throws exception`() {
        // Truncated JPEG: SOI (FF D8) followed by random scanlines, but connection dropped midway (no FF D9)
        val truncatedJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0x12, 0x34, 0x56, 0x78, 0x43, 0x21, 0x55
        )
        val file = File(tempDir, "half.jpg").apply { writeBytes(truncatedJpeg) }
        try {
            MediaIntegrityValidator.validate(file, mimeType = "image/jpeg")
            fail("Expected DownloadHttpException for truncated JPEG missing EOI")
        } catch (e: DownloadHttpException) {
            assertTrue(e.message?.contains("EOI") == true)
        }
    }

    @Test
    fun `valid png passes validation`() {
        val validPng = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
        )
        val file = File(tempDir, "valid.png").apply { writeBytes(validPng) }
        MediaIntegrityValidator.validate(file, mimeType = "image/png")
    }

    @Test
    fun `truncated png missing IEND throws exception`() {
        val truncatedPng = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
            0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        )
        val file = File(tempDir, "half.png").apply { writeBytes(truncatedPng) }
        try {
            MediaIntegrityValidator.validate(file, mimeType = "image/png")
            fail("Expected DownloadHttpException for truncated PNG missing IEND")
        } catch (e: DownloadHttpException) {
            assertTrue(e.message?.contains("IEND") == true)
        }
    }
}

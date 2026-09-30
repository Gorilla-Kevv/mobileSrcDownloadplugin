package com.clipdown.downloader.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MediaRemuxerTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("cd-remux-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, body: ByteArray): File =
        File(dir, name).apply { writeBytes(body) }

    @Test
    fun concatSegments_preservesOrder() {
        val a = file("a.ts", ByteArray(10) { 1 })
        val b = file("b.ts", ByteArray(20) { 2 })
        val c = file("c.ts", ByteArray(5) { 3 })
        val out = File(dir, "merged.ts")

        assertTrue(MediaRemuxer.concatSegments(listOf(a, b, c), out))
        assertArrayEquals(ByteArray(10) { 1 } + ByteArray(20) { 2 } + ByteArray(5) { 3 }, out.readBytes())
    }

    @Test
    fun concatSegments_emptyListFails() {
        assertFalse(MediaRemuxer.concatSegments(emptyList(), File(dir, "empty.ts")))
    }

    @Test
    fun concatInitAndMedia_initFirst() {
        val init = file("init.mp4", ByteArray(8) { 9 })
        val media = file("media.m4s", ByteArray(12) { 4 })
        val out = File(dir, "out.mp4")

        assertTrue(MediaRemuxer.concatInitAndMedia(init, media, out))
        assertArrayEquals(ByteArray(8) { 9 } + ByteArray(12) { 4 }, out.readBytes())
    }

    @Test
    fun concatInitAndMedia_missingInputFails() {
        assertFalse(
            MediaRemuxer.concatInitAndMedia(
                File(dir, "nope.mp4"),
                file("media.m4s", ByteArray(4)),
                File(dir, "out.mp4")
            )
        )
    }
}

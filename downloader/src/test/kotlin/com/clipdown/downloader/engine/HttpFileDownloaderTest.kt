package com.clipdown.downloader.engine

import com.clipdown.downloader.TestHttpServer
import com.clipdown.downloader.TestResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

class HttpFileDownloaderTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("cd-http-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun downloader(): HttpFileDownloader = HttpFileDownloader(OkHttpClient())

    private fun payload(size: Int = 64 * 1024): ByteArray =
        Random(42).nextBytes(ByteArray(size))

    @Test
    fun fullDownload_writesAllBytes() = runBlocking {
        val data = payload()
        TestHttpServer { TestResponse(body = data, contentType = "video/mp4") }.use { server ->
            val out = File(dir, "a.mp4")
            val state = HttpFileDownloader.State()
            val size = downloader().download(server.url("/a.mp4"), out, emptyMap(), state)

            assertEquals(data.size.toLong(), size)
            assertArrayEquals(data, out.readBytes())
            assertEquals(data.size.toLong(), state.downloaded)
            assertEquals(data.size.toLong(), state.total)
        }
    }

    @Test
    fun resume_sendsRangeAndAppends() = runBlocking {
        val data = payload(10_000)
        TestHttpServer { TestResponse(body = data) }.use { server ->
            val out = File(dir, "a.mp4")
            out.writeBytes(data.copyOfRange(0, 4_000))

            val state = HttpFileDownloader.State()
            downloader().download(server.url("/a.mp4"), out, emptyMap(), state)

            assertEquals("bytes=4000-", server.requests.last().headers["range"])
            assertArrayEquals(data, out.readBytes())
            assertEquals(data.size.toLong(), state.total)
        }
    }

    @Test
    fun serverWithoutRange_overwritesFromZero() = runBlocking {
        val data = payload(8_000)
        TestHttpServer { TestResponse(body = data, acceptRanges = false) }.use { server ->
            val out = File(dir, "a.mp4")
            out.writeBytes(ByteArray(3_000)) // 脏数据：长度小于目标，若被续传追加则结果必错

            val state = HttpFileDownloader.State()
            downloader().download(server.url("/a.mp4"), out, emptyMap(), state)

            assertArrayEquals(data, out.readBytes())
            assertEquals(data.size.toLong(), state.total)
        }
    }

    @Test
    fun httpError_throwsWithCode() = runBlocking {
        TestHttpServer { TestResponse(code = 404) }.use { server ->
            var code: Int? = null
            val ex = runCatching {
                downloader().download(server.url("/gone.mp4"), File(dir, "gone.mp4"), emptyMap(), HttpFileDownloader.State())
            }.exceptionOrNull()
            code = (ex as? DownloadHttpException)?.code
            assertEquals(404, code)
        }
    }

    @Test
    fun cancel_throwsAndLeavesPartialFile() = runBlocking {
        val data = payload(256 * 1024)
        TestHttpServer {
            TestResponse(body = data, chunkSize = 32 * 1024, chunkDelayMs = 60L)
        }.use { server ->
            val out = File(dir, "slow.mp4")
            val state = HttpFileDownloader.State()
            var captured: Throwable? = null

            val job = launch(Dispatchers.IO) {
                captured = runCatching {
                    downloader().download(server.url("/slow.mp4"), out, emptyMap(), state)
                }.exceptionOrNull()
            }
            delay(150)
            job.cancelAndJoin()

            assertTrue("期望抛出 DownloadCanceledException，实际：$captured", captured is DownloadCanceledException)
            assertTrue("取消后不应写满文件", out.length() < data.size)
        }
    }

    @Test
    fun probe_readsMetaHeaders() {
        val data = payload(5_000)
        TestHttpServer { TestResponse(body = data, contentType = "video/mp4") }.use { server ->
            val r = downloader().probe(server.url("/a.mp4"), mapOf("Referer" to "https://x.com"))
            assertEquals(200, r.code)
            assertEquals(5_000L, r.contentLength)
            assertEquals("video/mp4", r.mimeType)
            assertTrue(r.acceptRanges)
        }
    }

    @Test
    fun probe_unknownResource_returnsErrorCode() {
        TestHttpServer { TestResponse(code = 403) }.use { server ->
            val r = downloader().probe(server.url("/blocked.mp4"), emptyMap())
            assertEquals(403, r.code)
        }
    }
}

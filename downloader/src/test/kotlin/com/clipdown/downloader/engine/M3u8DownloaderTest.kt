package com.clipdown.downloader.engine

import com.clipdown.downloader.TestHttpServer
import com.clipdown.downloader.TestResponse
import com.clipdown.parser.stream.M3u8Key
import com.clipdown.parser.stream.M3u8Segment
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class M3u8DownloaderTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("cd-m3u8-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun downloader(): M3u8Downloader = M3u8Downloader(OkHttpClient(), 3)

    private fun mediaPlaylist(segments: List<String>, keyUri: String? = null): String {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:4\n")
        if (keyUri != null) sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"$keyUri\"\n")
        segments.forEach { sb.append("#EXTINF:3.0,\n").append(it).append("\n") }
        sb.append("#EXT-X-ENDLIST\n")
        return sb.toString()
    }

    private fun aesEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }

    @Test
    fun sequenceIv_encodesBigEndianInLastFourBytes() {
        val d = downloader()
        val zero = d.sequenceIv(0)
        assertEquals(16, zero.size)
        assertTrue(zero.take(12).all { it == 0.toByte() })

        val iv = d.sequenceIv(0x00010203)
        assertEquals(0.toByte(), iv[12])
        assertEquals(1.toByte(), iv[13])
        assertEquals(2.toByte(), iv[14])
        assertEquals(3.toByte(), iv[15])
    }

    @Test
    fun hexToBytes_parsesWithOrWithoutPrefix() {
        val d = downloader()
        val hex = "000102030405060708090a0b0c0d0e0f"
        val a = d.hexToBytes(hex)
        val b = d.hexToBytes("0x$hex")
        assertArrayEquals(a, b)
        assertEquals(16, a.size)
        assertEquals(0.toByte(), a[0])
        assertEquals(0x0f.toByte(), a[15])
    }

    @Test
    fun hexToBytes_shortInputPadsWithZeros() {
        val d = downloader()
        val out = d.hexToBytes("00ff")
        assertEquals(16, out.size)
        assertEquals(0xff.toByte(), out[1])
        assertEquals(0.toByte(), out[2])
    }

    @Test
    fun decrypt_aes128WithSequenceIv() {
        val key = ByteArray(16) { it.toByte() }
        val plain = ByteArray(64) { (it % 251).toByte() }
        val cipherText = aesEncrypt(plain, key, downloader().sequenceIv(0))
        val segment = M3u8Segment(
            uri = "http://127.0.0.1:1/seg0.ts",
            durationSec = 3.0,
            key = M3u8Key(method = "AES-128", uri = "/key.bin", ivHex = null)
        )

        TestHttpServer { path ->
            when (path) {
                "/key.bin" -> TestResponse(body = key)
                else -> TestResponse(code = 404)
            }
        }.use { server ->
            val resolved = segment.copy(key = segment.key!!.copy(uri = server.url("/key.bin")))
            val out = downloader().decrypt(cipherText, resolved, emptyMap(), 0)
            assertArrayEquals(plain, out)
        }
    }

    @Test
    fun decrypt_explicitIvIsHonored() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val ivHex = "000102030405060708090a0b0c0d0e0f"
        val iv = downloader().hexToBytes(ivHex)
        val plain = ByteArray(48) { (it * 3).toByte() }
        val cipherText = aesEncrypt(plain, key, iv)
        val segment = M3u8Segment(
            uri = "http://127.0.0.1:1/seg0.ts",
            durationSec = 3.0,
            key = M3u8Key(method = "AES-128", uri = "/key.bin", ivHex = "0x$ivHex")
        )

        TestHttpServer { path ->
            if (path == "/key.bin") TestResponse(body = key) else TestResponse(code = 404)
        }.use { server ->
            val resolved = segment.copy(key = segment.key!!.copy(uri = server.url("/key.bin")))
            val out = downloader().decrypt(cipherText, resolved, emptyMap(), 7)
            assertArrayEquals(plain, out)
        }
    }

    @Test
    fun decrypt_keyUnavailable_fallsBackToRaw() {
        val raw = ByteArray(32) { 7 }
        val segment = M3u8Segment(
            uri = "http://127.0.0.1:1/seg0.ts",
            durationSec = 3.0,
            key = M3u8Key(method = "AES-128", uri = "/missing-key.bin", ivHex = null)
        )
        TestHttpServer { TestResponse(code = 404) }.use { server ->
            val resolved = segment.copy(key = segment.key!!.copy(uri = server.url("/missing-key.bin")))
            assertArrayEquals(raw, downloader().decrypt(raw, resolved, emptyMap(), 0))
        }
    }

    @Test
    fun download_mediaPlaylist_concatenatesSegmentsInOrder() = runBlocking {
        val s0 = ByteArray(100) { 1 }
        val s1 = ByteArray(120) { 2 }
        val s2 = ByteArray(80) { 3 }
        val expected = s0 + s1 + s2

        TestHttpServer { path ->
            when (path) {
                "/media.m3u8" -> TestResponse(
                    body = mediaPlaylist(listOf("/seg0.ts", "/seg1.ts", "/seg2.ts")).toByteArray(),
                    contentType = "application/vnd.apple.mpegurl"
                )
                "/seg0.ts" -> TestResponse(body = s0, contentType = "video/mp2t")
                "/seg1.ts" -> TestResponse(body = s1, contentType = "video/mp2t")
                "/seg2.ts" -> TestResponse(body = s2, contentType = "video/mp2t")
                else -> TestResponse(code = 404)
            }
        }.use { server ->
            val work = File(dir, "work")
            val out = File(dir, "out.mp4")
            val state = HttpFileDownloader.State()
            // 播放列表内段地址以 / 开头，M3u8Parser.resolve 会补全为同 host 绝对地址
            val ok = downloader().download(
                playlistUrl = server.url("/media.m3u8"),
                headers = emptyMap(),
                workDir = work,
                output = out,
                state = state
            )

            assertTrue(ok)
            assertArrayEquals(expected, out.readBytes())
            assertEquals(expected.size.toLong(), state.total)
            assertEquals(expected.size.toLong(), state.downloaded)
        }
    }

    @Test
    fun download_masterPlaylist_picksHighestBandwidth() = runBlocking {
        val hi = ByteArray(64) { 9 }
        val lo = ByteArray(64) { 1 }
        val master = "#EXTM3U\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480\n" +
            "/480.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\n" +
            "/720.m3u8\n"

        TestHttpServer { path ->
            when (path) {
                "/master.m3u8" -> TestResponse(body = master.toByteArray(), contentType = "application/vnd.apple.mpegurl")
                "/720.m3u8" -> TestResponse(
                    body = mediaPlaylist(listOf("/hi.ts")).toByteArray(),
                    contentType = "application/vnd.apple.mpegurl"
                )
                "/480.m3u8" -> TestResponse(
                    body = mediaPlaylist(listOf("/lo.ts")).toByteArray(),
                    contentType = "application/vnd.apple.mpegurl"
                )
                "/hi.ts" -> TestResponse(body = hi, contentType = "video/mp2t")
                "/lo.ts" -> TestResponse(body = lo, contentType = "video/mp2t")
                else -> TestResponse(code = 404)
            }
        }.use { server ->
            val out = File(dir, "master.mp4")
            val ok = downloader().download(
                playlistUrl = server.url("/master.m3u8"),
                headers = emptyMap(),
                workDir = File(dir, "work2"),
                output = out,
                state = HttpFileDownloader.State()
            )
            assertTrue(ok)
            assertArrayEquals(hi, out.readBytes())
        }
    }

    @Test
    fun download_segmentFailure_propagatesHttpException() = runBlocking {
        // 分片失败不做静默降级：异常上抛，由 DownloadEngine 统一 runCatching + 退避重试
        TestHttpServer { path ->
            when (path) {
                "/media.m3u8" -> TestResponse(
                    body = mediaPlaylist(listOf("/seg0.ts", "/bad.ts")).toByteArray(),
                    contentType = "application/vnd.apple.mpegurl"
                )
                "/seg0.ts" -> TestResponse(body = ByteArray(32), contentType = "video/mp2t")
                else -> TestResponse(code = 500)
            }
        }.use { server ->
            val ex = runCatching {
                downloader().download(
                    playlistUrl = server.url("/media.m3u8"),
                    headers = emptyMap(),
                    workDir = File(dir, "work3"),
                    output = File(dir, "fail.mp4"),
                    state = HttpFileDownloader.State()
                )
            }.exceptionOrNull()
            assertTrue("期望分片失败抛出 DownloadHttpException，实际：$ex", ex is DownloadHttpException)
            assertEquals(500, (ex as DownloadHttpException).code)
        }
    }
}

package com.clipdown.parser

import com.clipdown.parser.parsers.YoutubeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeParserTest {

    private val parser = YoutubeParser()

    /** 真实 Piped 响应（api.piped.private.coffee，2026-09-27 抓取）：format 取值为 MP4 / HLS / MPEG_4 */
    @Test
    fun `解析 Piped 响应 HLS 条目标记为播放列表`() {
        val fixture = fixture("piped_streams.json")
        val http = FakeHttp { url ->
            when {
                url.contains("pipedapi.kavin.rocks") -> notFound()        // 真实已失效
                url.contains("adminforge") -> notFound()
                url.contains("private.coffee") -> ok(fixture)             // 真实存活实例
                else -> notFound()
            }
        }
        val result = parser.parse(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            testContext(http)
        )

        // HLS 条目（master.m3u8）必须标记 isPlaylist，否则会被当直链下载出假 mp4（阶段 4 的 bug）
        val hls = result.media.filter { it.isPlaylist }
        assertTrue("HLS 条目应标记 isPlaylist: ${result.media.map { it.container to it.isPlaylist }}", hls.isNotEmpty())
        assertTrue(hls.all { it.container == "m3u8" })

        // 直链 mp4 条目存在且不标记播放列表
        val direct = result.media.filter { !it.isPlaylist }
        assertTrue(direct.isNotEmpty())
        assertTrue(direct.all { it.container == "mp4" })

        assertTrue(result.title!!.contains("Never Gonna Give You Up"))
        assertEquals("Rick Astley", result.author)
        assertEquals("youtube-relay-v1-piped", result.resolverId)
    }

    @Test
    fun `实例降级链 第一个失效自动切下一个`() {
        // 真实排序中 kavin.rocks 已 526 失效，private.coffee 存活——验证降级可达
        val fixture = fixture("piped_streams.json")
        val http = FakeHttp { url ->
            if (url.contains("private.coffee")) ok(fixture) else notFound()
        }
        val result = parser.parse("https://youtu.be/dQw4w9WgXcQ", testContext(http))
        assertTrue(result.media.isNotEmpty())
    }

    @Test
    fun `全部实例失效时抛业务异常`() {
        val http = FakeHttp { notFound() }
        val err = runCatching {
            parser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ", testContext(http))
        }.exceptionOrNull()
        assertTrue(err!!.message!!.contains("远端解析服务"))
    }

    @Test
    fun `视频 ID 提取 多种链接形态`() {
        assertTrue(parser.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(parser.canHandle("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(parser.canHandle("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
        assertTrue(!parser.canHandle("https://www.youtube.com/channel/UCx"))
    }
}

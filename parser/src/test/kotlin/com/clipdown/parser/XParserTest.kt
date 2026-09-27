package com.clipdown.parser

import com.clipdown.parser.parsers.XParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XParserTest {

    private val parser = XParser()

    @Test
    fun `canHandle 匹配 status 链接`() {
        assertTrue(parser.canHandle("https://x.com/astro_anil/status/2094077925989515691"))
        assertTrue(parser.canHandle("https://twitter.com/a/statuses/123456"))
        assertTrue(!parser.canHandle("https://x.com/home"))
    }

    /**
     * guest token 已知答案：该 token 在 2026-09-27 经真实 syndication 接口验证可用
     * （返回 200 且携带完整 mediaDetails）。
     */
    @Test
    fun `guestToken 已知答案`() {
        assertEquals("52qqmtfi7esad5b", parser.guestToken("2094077925989515691"))
    }

    @Test
    fun `guestToken 无点无零且可复现`() {
        val t1 = parser.guestToken("2103834028164239865")
        val t2 = parser.guestToken("2103834028164239865")
        assertEquals(t1, t2)
        assertTrue(!t1.contains("."))
        assertTrue(!t1.contains("0"))
    }

    @Test
    fun `解析真实 syndication 响应 提取视频变体`() {
        val fixture = fixture("syndication_video_tweet.json")
        val http = FakeHttp { if (it.contains("cdn.syndication.twimg.com")) ok(fixture) else notFound() }
        val result = parser.parse(
            "https://x.com/astro_anil/status/2094077925989515691",
            testContext(http)
        )

        val videos = result.media.filter { it.kind.name == "VIDEO" }
        assertTrue("应有视频变体: ${result.media.map { it.kind }}", videos.isNotEmpty())
        assertEquals("Anil Menon", result.author)
        assertTrue(result.title!!.contains("Chez ISS"))
        assertEquals("x-syndication-v1", result.resolverId)
        // 变体按码率降序
        assertTrue(videos.first().rank >= videos.last().rank)
    }

    @Test
    fun `无媒体推文抛业务异常`() {
        val noMedia = """{"text":"纯文本推文","user":{"name":"a","screen_name":"a"}}"""
        val http = FakeHttp { if (it.contains("syndication")) ok(noMedia) else notFound() }
        val err = runCatching {
            parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))
        }.exceptionOrNull()
        assertTrue(err!!.message!!.contains("没有可下载的媒体"))
    }
}

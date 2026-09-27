package com.clipdown.parser

import com.clipdown.parser.parsers.InstagramParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramParserTest {

    private val parser = InstagramParser()

    private val embedHtml = """
        <html><head>
        <meta property="og:title" content="Test Reel by NASA" />
        </head><body>
        <script type="text/javascript">window.__additionalDataLoaded('/p/ABC123/',{"username":"nasa","video_url":"https://example.com/video_720.mp4","display_url":"https://example.com/pic.jpg"});</script>
        <img class="EmbeddedMediaImage" src="https://example.com/embedded_img.jpg" />
        </body></html>
    """.trimIndent()

    @Test
    fun `canHandle 覆盖四种作品形态`() {
        assertTrue(parser.canHandle("https://www.instagram.com/p/ABC123/"))
        assertTrue(parser.canHandle("https://www.instagram.com/reel/DczssLAbCdE/"))
        assertTrue(parser.canHandle("https://www.instagram.com/reels/DczssLAbCdE/"))
        assertTrue(parser.canHandle("https://instagr.am/p/ABC123/"))
        assertTrue(!parser.canHandle("https://www.instagram.com/explore/"))
    }

    @Test
    fun `无 Cookie 走 embed 通道 提取视频与图片`() {
        val http = FakeHttp { url ->
            if (url.contains("/embed/captioned/")) ok(embedHtml) else notFound()
        }
        val result = parser.parse(
            "https://www.instagram.com/reel/DczssLAbCdE/",
            testContext(http, logs = mutableListOf())
        )

        assertTrue(result.resolverId.endsWith("-embed"))
        assertTrue(result.media.any { it.kind.name == "VIDEO" && it.url.contains("video_720.mp4") })
        assertTrue(result.media.any { it.kind.name == "IMAGE" })
        assertEquals("Test Reel by NASA", result.title)
    }

    @Test
    fun `带 Cookie 且 embed 失败时降级路径完整`() {
        // Cookie 存在但 GraphQL 不可用（fake 全 404）→ 走 embed → embed 也 404 → oEmbed 404 → 抛业务异常
        val http = FakeHttp { notFound() }
        val err = runCatching {
            parser.parse(
                "https://www.instagram.com/p/ABC123/",
                testContext(http, cookies = mapOf(com.clipdown.parser.model.Platform.INSTAGRAM to "sessionid=x"))
            )
        }.exceptionOrNull()
        assertTrue(err!!.message!!.contains("解析失败"))
    }
}

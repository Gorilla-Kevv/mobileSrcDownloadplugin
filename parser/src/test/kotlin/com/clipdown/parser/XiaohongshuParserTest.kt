package com.clipdown.parser

import com.clipdown.parser.http.HttpResponse
import com.clipdown.parser.model.Platform
import com.clipdown.parser.parsers.XiaohongshuParser
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaohongshuParserTest {

    private val parser = XiaohongshuParser()

    @Test
    fun `canHandle 恒真 由路由层先行平台判定`() {
        assertTrue(parser.canHandle("anything"))
    }

    @Test
    fun `解析真实笔记页 提取图集与标题`() {
        val fixture = fixture("xhs_note.html")
        val homepageHeaders = mutableMapOf<String, String>()
        val http = FakeHttp { url ->
            if (url == "https://www.xiaohongshu.com/") {
                // WAF 预热请求：下发 acw_tc 种子
                HttpResponse(
                    200, "<html>home</html>",
                    headersOf("Set-Cookie", "acw_tc=seed123;path=/;HttpOnly"),
                    "https://www.xiaohongshu.com/"
                )
            } else {
                homepageHeaders.putAll(emptyMap())
                ok(fixture)
            }
        }
        val cookie = "a1=abc; web_session=xyz"
        val result = parser.parse(
            "https://www.xiaohongshu.com/explore/6ab7ffb0000000000b006b27?xsec_token=T",
            testContext(http, cookies = mapOf(Platform.XIAOHONGSHU to cookie))
        )

        assertEquals("搬一下之前画过的小樱", result.title)
        assertEquals("xiaohongshu", result.platform.id)
        val images = result.media.filter { it.kind.name == "IMAGE" }
        assertTrue("应从 urlDefault 提取到图片: ${result.media.size}", images.isNotEmpty())
        assertTrue(images.all { !it.url.contains("?") })  // 裁剪参数被去掉
    }

    @Test
    fun `预热种子 Cookie 合并进笔记请求`() {
        val fixture = fixture("xhs_note.html")
        var noteCookie: String? = null
        val http = FakeHttp { url ->
            if (url == "https://www.xiaohongshu.com/") {
                HttpResponse(
                    200, "home",
                    headersOf("Set-Cookie", "acw_tc=seed_acw_123;path=/"),
                    "https://www.xiaohongshu.com/"
                )
            } else {
                ok(fixture)
            }
        }
        // 通过自定义 FakeHttp 捕获笔记请求头：包一层
        val capturing = object : com.clipdown.parser.http.HttpFacade by http {
            override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResponse {
                if (url.contains("/explore/")) noteCookie = headers["Cookie"]
                return http.get(url, headers, followRedirects)
            }
        }
        parser.parse(
            "https://www.xiaohongshu.com/explore/6ab7ffb0000000000b006b27?xsec_token=T",
            testContext(capturing, cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc; web_session=xyz"))
        )
        assertTrue("笔记请求应合并预热种子: $noteCookie", noteCookie!!.contains("acw_tc=seed_acw_123"))
        assertTrue(noteCookie!!.contains("a1=abc"))
    }

    @Test
    fun `视频笔记的 masterUrl 提取`() {
        val state = """
            window.__INITIAL_STATE__={"note":{"video":{"media":{"stream":{"h264":[
            {"masterUrl":"https://sns-video.xhscdn.com/main.mp4","height":1080},
            {"masterUrl":"https://sns-video.xhscdn.com/backup.mp4","height":720}
            ]}}}}}
        """.trimIndent()
        val html = "<html><head><meta property=\"og:title\" content=\"V\" /></head><body><script>$state</script></body></html>"
        val http = FakeHttp { url ->
            if (url == "https://www.xiaohongshu.com/") {
                HttpResponse(200, "home", headersOf("Set-Cookie", "acw_tc=s;path=/"), "")
            } else ok(html)
        }
        val result = parser.parse(
            "https://www.xiaohongshu.com/explore/abc?xsec_token=T",
            testContext(http, cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc"))
        )
        val videos = result.media.filter { it.kind.name == "VIDEO" }
        assertEquals(2, videos.size)
        assertTrue(videos.all { it.url.startsWith("https://sns-video") })
    }
}

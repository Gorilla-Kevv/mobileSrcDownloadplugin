package com.clipdown.parser

import com.clipdown.parser.parsers.HtmlUtil
import com.clipdown.parser.parsers.IgRiskGuard
import com.clipdown.parser.parsers.InstagramParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InstagramParserTest {

    private val parser = InstagramParser()

    @Before
    fun resetGuard() {
        IgRiskGuard.reset()
    }

    @Test
    fun `shortcodeToMediaId 算法换算正确`() {
        assertEquals("4003056927646516681", parser.shortcodeToMediaId("DeNtxEMCYXJ"))
        assertEquals("3977719445419992900", parser.shortcodeToMediaId("DczssLAbCdE"))
        assertEquals("17522103", parser.shortcodeToMediaId("ABC123"))
    }

    @Test
    fun `双重转义 URL 完整还原（修复 9 reels 403 根因）`() {
        // IG 页面 JSON-in-JS 双重转义：db 里曾存成 https:\\/\\/... 导致下载 403
        assertEquals(
            "https://scontent.cdninstagram.com/o1/v/t2/f2/m367/AQMN.mp4?oe=6AC018D6",
            HtmlUtil.unescapeJsonOf(
                "https:\\\\/\\\\/scontent.cdninstagram.com\\\\/o1\\\\/v\\\\/t2\\\\/f2\\\\/m367\\\\/AQMN.mp4?oe=6AC018D6"
            )
        )
        assertEquals("%", HtmlUtil.unescapeJsonOf("\\\\u0025"))
        // 单层形态回归（XHS 场景）
        assertEquals("/a/b", HtmlUtil.unescapeJsonOf("\\u002Fa\\/b"))
    }

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
        // embed 含视频时只保留视频（封面图是视频的帧，不应作为独立媒体——修复 10c）
        assertTrue(result.media.any { it.kind.name == "VIDEO" && it.url.contains("video_720.mp4") })
        assertTrue(
            "视频帖不应带封面图媒体: ${result.media.map { it.kind }}",
            result.media.none { it.kind.name == "IMAGE" }
        )
        assertEquals("Test Reel by NASA", result.title)
    }

    @Test
    fun `webFetcher 渲染页含视频时优先返回视频`() {
        val rendered = """
            <html><head>
            <meta property="og:title" content="My Reel Video" />
            <meta property="og:video" content="https://cdn.instagram.com/reel_video.mp4" />
            <meta property="og:image" content="https://cdn.instagram.com/reel_cover.jpg" />
            </head><body>
            <script type="text/javascript">window.__additionalDataLoaded('extra',{"username":"nasa"});</script>
            </body></html>
        """.trimIndent()
        val http = FakeHttp { url ->
            // embed 与 oEmbed 都不含视频，逼出 WebView 通道
            if (url.contains("/embed/captioned/")) ok("<html><body>no media</body></html>") else notFound()
        }
        val result = parser.parse(
            "https://www.instagram.com/reel/DReal111/",
            testContext(
                http,
                webFetcher = { url ->
                    if (url.contains("instagram.com/reel/DReal111")) rendered else null
                }
            )
        )
        val videos = result.media.filter { it.kind.name == "VIDEO" }
        assertTrue("应通过 WebView 渲染页拿到视频: ${result.media.map { it.kind }}", videos.isNotEmpty())
        assertEquals("https://cdn.instagram.com/reel_video.mp4", videos.first().url)
        assertEquals("ig-local-v1-page", result.resolverId)
        assertEquals("My Reel Video", result.title)
    }

    @Test
    fun `webFetcher 支持 DOM video 与 source 标签兜底提取`() {
        val rendered = """
            <html><head><title>DOM Video Post</title></head><body>
            <div class="video-container">
                <video src="https://cdn.example.com/dom_direct.mp4"></video>
            </div>
            </body></html>
        """.trimIndent()
        val http = FakeHttp { url ->
            if (url.contains("/embed/captioned/")) ok("<html><body>no media</body></html>") else notFound()
        }
        val result = parser.parse(
            "https://www.instagram.com/reel/DDom111/",
            testContext(
                http,
                webFetcher = { url -> if (url.contains("DDom111")) rendered else null }
            )
        )
        val videos = result.media.filter { it.kind.name == "VIDEO" }
        assertTrue("DOM 中的 video 标签应成功提取", videos.any { it.url.contains("dom_direct.mp4") })
    }

    @Test
    fun `带 Cookie 且全部失败时给出友好提示`() {
        val http = FakeHttp { notFound() }
        val err = runCatching {
            parser.parse(
                "https://www.instagram.com/p/ABC123/",
                testContext(http, cookies = mapOf(com.clipdown.parser.model.Platform.INSTAGRAM to "sessionid=x"))
            )
        }.exceptionOrNull()
        assertTrue(err!!.message!!.contains("解析失败"))
    }

    @Test
    fun `items0 内嵌推荐块时只提取正帖媒体（修复 10）`() {
        // 2026-09 结构：items[0] 对象内嵌含陌生 code 的数组块（video_versions=null 的空壳），
        // 正帖 code 与媒体数据在对象顶层——旧逻辑全段扫描会把陌生媒体/封面混进来
        val rendered = """
            <html><head><meta property="og:title" content="Polluted Page" /></head><body>
            <script type="text/javascript">window.__additionalDataLoaded('extra',{"xdt_api__v1__media__shortcode__web_info":{"items":[{
                "preview_comments":{"rows":[{"code":"StrangerA","video_versions":null,"media_type":1},
                {"code":"StrangerB","video_versions":null,"media_type":1}],
                "stranger_media":[{"code":"StrangerC","video_versions":[{"width":720,"height":1280,"url":"https:\\/\\/cdn.example.com\\/stranger_c.mp4"}],
                "image_versions2":{"candidates":[{"width":720,"height":1280,"url":"https:\\/\\/cdn.example.com\\/cover_c.jpg"}]}}]},
                "code":"DOwnPost99",
                "video_versions":[{"width":720,"height":1280,"url":"https:\\/\\/cdn.example.com\\/own_reel.mp4"}],
                "image_versions2":{"candidates":[{"width":720,"height":1280,"url":"https:\\/\\/cdn.example.com\\/own_cover.jpg"}]}}]}});</script>
            </body></html>
        """.trimIndent()
        val http = FakeHttp { url ->
            if (url.contains("/embed/captioned/")) ok("<html><body>no media</body></html>") else notFound()
        }
        val result = parser.parse(
            "https://www.instagram.com/reel/DOwnPost99/",
            testContext(
                http,
                webFetcher = { url -> if (url.contains("DOwnPost99")) rendered else null }
            )
        )
        assertTrue(
            "媒体不应包含推荐流内容: ${result.media.map { it.url }}",
            result.media.all { !it.url.contains("stranger") && !it.url.contains("cover_c") }
        )
        assertTrue("应保留正帖视频", result.media.any { it.url.contains("own_reel.mp4") })
        assertTrue("应保留正帖图片", result.media.any { it.url.contains("own_cover.jpg") })
    }

    private val lsdHtml = """<html><script>require("FDSwitcher","LSD",[],{"token":"LSDTOK123"});</script></html>"""

    private fun graphqlHttp(json: String) = FakeHttp { url ->
        when {
            url.contains("/api/v1/media/") -> notFound()
            url.contains("/api/graphql") -> ok(json)
            url.contains("/graphql/query") -> ok(json)
            url.contains("/p/") -> ok(lsdHtml)
            else -> notFound()
        }
    }

    private val igCookie = mapOf(com.clipdown.parser.model.Platform.INSTAGRAM to "sessionid=x; csrftoken=y")

    @Test
    fun `移动端 REST info 接口原生 items 优先提取`() {
        val restJson = """
            {"items": [{
                "video_versions": [{"width": 720, "height": 1280, "url": "https:\\/\\/cdn.example.com\\/v_rest.mp4"}],
                "caption": {"text": "Rest API Reel"},
                "user": {"username": "nasa"}
            }]}
        """.trimIndent()
        val http = FakeHttp { url ->
            if (url.contains("/api/v1/media/")) ok(restJson)
            else notFound()
        }
        val result = parser.parse(
            "https://www.instagram.com/reel/DczssLAbCdE/",
            testContext(http, cookies = igCookie, logs = mutableListOf())
        )
        assertEquals("ig-local-v1-v1-info", result.resolverId)
        val videos = result.media.filter { it.kind.name == "VIDEO" }
        assertEquals(1, videos.size)
        assertTrue(videos[0].url.contains("v_rest.mp4"))
        assertEquals("Rest API Reel", result.title)
        assertEquals("nasa", result.author)
    }

    @Test
    fun `GraphQL 2026 xig_polaris_media 结构提取`() {
        val polarisJson = """
            {"data":{"xig_polaris_media":{"if_not_gated_logged_out":{
                "code":"DeNtxEMCYXJ",
                "video_versions":[{"width":1080,"height":1920,"url":"https:\\/\\/cdn.example.com\\/polaris_reel.mp4"}],
                "caption":{"text":"Polaris video"},
                "owner":{"username":"nasa"}
            }}}}
        """.trimIndent()
        val result = parser.parse(
            "https://www.instagram.com/p/DeNtxEMCYXJ/",
            testContext(graphqlHttp(polarisJson), cookies = igCookie, logs = mutableListOf())
        )
        assertEquals("ig-local-v1-private", result.resolverId)
        val videos = result.media.filter { it.kind.name == "VIDEO" }
        assertEquals(1, videos.size)
        assertTrue(videos[0].url.contains("polaris_reel.mp4"))
        assertEquals("Polaris video", result.title)
    }

    @Test
    fun `GraphQL carousel_media 混合相册（图+视频）全部提取`() {
        // 2026 web_info 结构：图集子项在 carousel_media 数组；视频子项的封面不作为独立媒体
        val json = """
            {"data":{"xdt_shortcode_media":{"code":"DeNtxEMCYXJ",
             "carousel_media":[
                {"video_versions":null,"image_versions2":{"candidates":[{"width":1080,"height":1080,"url":"https:\\/\\/cdn.example.com\\/a1.jpg"}]}},
                {"video_versions":[{"width":640,"height":640,"url":"https:\\/\\/cdn.example.com\\/v1.mp4"}],
                 "image_versions2":{"candidates":[{"width":640,"height":640,"url":"https:\\/\\/cdn.example.com\\/vcover.jpg"}]}},
                {"video_versions":null,"image_versions2":{"candidates":[{"width":1080,"height":1350,"url":"https:\\/\\/cdn.example.com\\/a3.jpg"}]}}
             ],"caption":{"text":"mixed"}}}}
        """.trimIndent()
        val result = parser.parse(
            "https://www.instagram.com/p/DeNtxEMCYXJ/?img_index=4",
            testContext(graphqlHttp(json), cookies = igCookie, logs = mutableListOf())
        )
        assertEquals("ig-local-v1-private", result.resolverId)
        val videos = result.media.filter { it.kind.name == "VIDEO" }
        val images = result.media.filter { it.kind.name == "IMAGE" }
        assertTrue("混合相册应含 1 视频: ${result.media.map { it.url }}", videos.size == 1 && videos[0].url.contains("v1.mp4"))
        assertTrue("混合相册应含 2 图且不含视频封面: ${result.media.map { it.url }}",
            images.size == 2 && images.none { it.url.contains("vcover") })
    }

    @Test
    fun `GraphQL 未知嵌套结构时正帖节点内递归兜底`() {
        val json = """
            {"data":{"xdt_shortcode_media":{"code":"ABC123",
             "media":{"nodes":[
                {"video_versions":[{"width":720,"height":720,"url":"https:\\/\\/cdn.example.com\\/x1.mp4"}]},
                {"image_versions2":{"candidates":[{"width":1080,"height":1080,"url":"https:\\/\\/cdn.example.com\\/x2.jpg"}]}}
             ]}}}}
        """.trimIndent()
        val result = parser.parse(
            "https://www.instagram.com/p/ABC123/",
            testContext(graphqlHttp(json), cookies = igCookie, logs = mutableListOf())
        )
        assertEquals(2, result.media.size)
        assertTrue(result.media.any { it.url.contains("x1.mp4") })
        assertTrue(result.media.any { it.url.contains("x2.jpg") })
    }
}

package com.clipdown.parser

import com.clipdown.parser.http.HttpResponse
import com.clipdown.parser.model.ParseException
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

    /**
     * 小红书同一张图会同时下发 urlPre（预览）与 urlDefault（默认画质）——
     * 两者只有路径 hash 段与 `!nd_prv_/!nd_dft_` 后缀不同，**文件 ID 相同**。
     * 修复前按 URL 字符串去重 → 每张图产出 2 项，图集竖条里每张图重复一次。
     */
    @Test
    fun `图集去重 同图 urlPre 与 urlDefault 只保留一份`() {
        val fixture = fixture("xhs_note.html")
        val http = FakeHttp { url ->
            if (url == "https://www.xiaohongshu.com/") {
                HttpResponse(200, "home", headersOf("Set-Cookie", "acw_tc=s;path=/"), "")
            } else ok(fixture)
        }
        val result = parser.parse(
            "https://www.xiaohongshu.com/explore/6ab7ffb0000000000b006b27?xsec_token=T",
            testContext(http, cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc"))
        )
        val images = result.media.filter { it.kind.name == "IMAGE" }
        // 夹具实际是 2 张图（urlDefault/urlPre 各 4 条），去重后应为 2
        assertEquals("图集不应出现重复图: ${images.map { it.url }}", 2, images.size)
        assertTrue("应保留默认画质 urlDefault: ${images.map { it.url }}", images.all { it.url.contains("nd_dft") })
    }

    /**
     * 视频笔记的 SSR 里同时有 masterUrl（视频）与 imageList（封面帧）。
     * 与 IG 修复 10c 同源：封面图混入会让 media 变成 [视频, 图] → isAlbumMultiSelect=true
     * → 弹图集竖条、跳过自动下载，用户下载到"视频 + 封面图"。
     */
    @Test
    fun `视频笔记只保留视频 封面图不落媒体`() {
        val state = """
            window.__INITIAL_STATE__={"noteDetailMap":{"n1":{"note":{"video":{"media":{"stream":{"h264":[
            {"masterUrl":"https://sns-video.xhscdn.com/main.mp4","height":1080},
            {"masterUrl":"https://sns-video.xhscdn.com/backup.mp4","height":720}
            ]}}},"imageList":[{"urlDefault":"https://sns-webpic-qc.xhscdn.com/a/notes_pre_post/FILEID!nd_dft_wlteh_jpg_3"}]}}}}
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
        val images = result.media.filter { it.kind.name == "IMAGE" }
        assertEquals(2, videos.size)
        assertTrue("封面图不应作为独立媒体: ${images.map { it.url }}", images.isEmpty())
        assertTrue("单视频帖应判为单选（走自动下载）", !result.isAlbumMultiSelect)
    }

    /**
     * 小红书失效笔记不返回 4xx，而是渲染 title="小红书 - 你访问的页面不见了" 的页
     * （设备实测落盘页确认）；跳到探索页时 title 是"小红书 - 你的生活兴趣社区"。
     * 两者都必须直接判定失败，不能落进媒体提取。
     */
    @Test
    fun `失效笔记页 直接判定笔记不存在`() {
        val html = "<html><head><title>小红书 - 你访问的页面不见了</title></head><body>" +
            "<script>window.__INITIAL_STATE__={\"noteDetailMap\":{}}</script></body></html>"
        val http = FakeHttp { url ->
            if (url == "https://www.xiaohongshu.com/") {
                HttpResponse(200, "home", headersOf("Set-Cookie", "acw_tc=s;path=/"), "")
            } else ok(html)
        }
        val err = runCatching {
            parser.parse(
                "https://www.xiaohongshu.com/explore/6ab7ffb0000000000b006b27",
                testContext(http, cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc"))
            )
        }.exceptionOrNull()
        assertTrue("应判定笔记不存在: $err", err is ParseException)
        assertTrue("文案应说明笔记不存在: ${err?.message}", err!!.message!!.contains("笔记不存在"))
    }

    /**
     * 未登录时小红书 302 到 /login，而**登录页的 title 与探索页完全相同**
     * （都是"小红书 - 你的生活兴趣社区"，2026-10 实测）。文案必须同时给出
     * "补 Cookie" 与 "重新复制链接" 两条可操作路径，不能只说链接失效。
     */
    @Test
    fun `登录墙或探索页 文案同时给出补Cookie与重取链接`() {
        val html = "<html><head><title>小红书 - 你的生活兴趣社区</title></head><body>" +
            "<script>window.__INITIAL_STATE__={\"user\":{\"loggedIn\":false}}</script></body></html>"
        val http = FakeHttp { url ->
            if (url == "https://www.xiaohongshu.com/") {
                HttpResponse(200, "home", headersOf("Set-Cookie", "acw_tc=s;path=/"), "")
            } else ok(html)
        }
        val err = runCatching {
            parser.parse(
                "https://www.xiaohongshu.com/discovery/item/6ac3c5c4000000001500ef52?xsec_token=T",
                testContext(http, cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc"))
            )
        }.exceptionOrNull()
        assertTrue("应判定为登录墙/跳转页: $err", err is ParseException)
        assertTrue("文案应提示补 Cookie: ${err?.message}", err!!.message!!.contains("Cookie"))
        assertTrue("文案应提示重取链接: ${err.message}", err.message!!.contains("xsec_token"))
    }

    /**
     * 小红书对"已删除 / 缺 xsec_token / 失效"的笔记会跳到 /404 或探索页，
     * 此时页面里是推荐流；没有守卫会把推荐流的封面图当成图集返回。
     */
    @Test
    fun `非笔记详情页 不把推荐流当图集返回`() {
        val feed = """
            window.__INITIAL_STATE__={"feed":{"feeds":[{"noteCard":{"cover":{"urlDefault":"https://sns-webpic-qc.xhscdn.com/f/notes_pre_post/FEED1!nd_dft_wlteh_jpg_3"}}}]}}
        """.trimIndent()
        // 标题刻意不落在"失效页/探索页"判定里，单独验证 noteDetailMap 守卫
        val html = "<html><head><meta property=\"og:title\" content=\"发现你感兴趣的内容 - 小红书\" /></head><body><script>$feed</script></body></html>"
        val http = FakeHttp { url ->
            if (url == "https://www.xiaohongshu.com/") {
                HttpResponse(200, "home", headersOf("Set-Cookie", "acw_tc=s;path=/"), "")
            } else ok(html)
        }
        val err = runCatching {
            parser.parse(
                "https://www.xiaohongshu.com/explore/abc",
                testContext(http, cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc"))
            )
        }.exceptionOrNull()
        assertTrue("应拒绝非笔记详情页: $err", err is ParseException)
        assertTrue("错误文案应提示链接失效: ${err?.message}", err!!.message!!.contains("笔记详情页"))
    }

    /**
     * 移动端 SSR 结构（2026-10 实测取证）：**没有 noteDetailMap**，图片在 `imageList[].url`
     * （带 fileId），标题是 `title`，作者是 `user.nickName`；且页面里还跟着推荐流
     * （objectPosition 递增的其它笔记）。桌面 UA 会被 302 到 /login，所以移动端结构是主路径。
     */
    @Test
    fun `移动端结构 提取图集并取对标题与作者`() {
        val state = """
            window.__INITIAL_STATE__={"note":{"noteId":"6ac3c5c4000000001500ef52","type":"normal",
            "atUserList":[{"userId":"5ac4c21f4eacab16ccebcb41","nickName":"糖包Rohan"}],
            "user":{"avatar":"https://sns-avatar-qc.xhscdn.com/avatar/x.jpg","userId":"67c01c94000000000d00ada2","nickName":"折纸"},
            "imageList":[{"fileId":"1040g008325us1dr444105pu03ia39bd2j1tngn8","width":3901,"height":5852,"url":"http://sns-webpic-qc.xhscdn.com/202610061338/36a12fdb/1040g008325us1dr444105pu03ia39bd2j1tngn8!h5_1080jpg"}],
            "title":"须臾的休憩","cover":{"fileId":"1040g008325us1dr444105pu03ia39bd2j1tngn8"}},
            "feed":[{"noteCard":{"title":"推荐流里的别的笔记","cover":{"url":"http://sns-webpic-qc.xhscdn.com/202610061338/ffff/OTHERNOTE0001!h5_1080jpg"}}}]}
        """.trimIndent()
        val html = "<html><head><title> - 小红书</title></head><body><script>$state</script></body></html>"
        val http = FakeHttp { url ->
            if (url == "https://www.xiaohongshu.com/") {
                HttpResponse(200, "home", headersOf("Set-Cookie", "acw_tc=s;path=/"), "")
            } else ok(html)
        }
        val result = parser.parse(
            "https://www.xiaohongshu.com/discovery/item/6ac3c5c4000000001500ef52?xsec_token=T",
            testContext(http, cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc"))
        )
        assertEquals("须臾的休憩", result.title)
        assertEquals("应取 user.nickName 而不是 atUserList 里被@的人", "折纸", result.author)
        val images = result.media.filter { it.kind.name == "IMAGE" }
        assertEquals("单图笔记应只出 1 项: ${images.map { it.url }}", 1, images.size)
        assertTrue(images[0].url.contains("1040g008325us1dr444105pu03ia39bd2j1tngn8"))
    }

    /** 移动端多图：imageList 里多个 fileId，应逐个产出且不重复 */
    @Test
    fun `移动端多图 按 fileId 去重后逐张产出`() {
        val state = """
            window.__INITIAL_STATE__={"note":{"noteId":"6ac3c5c4000000001500ef53",
            "user":{"userId":"u1","nickName":"折纸"},
            "imageList":[
            {"fileId":"AAAA0001","url":"http://sns-webpic-qc.xhscdn.com/202610061338/h1/AAAA0001!h5_1080jpg"},
            {"fileId":"AAAA0002","url":"http://sns-webpic-qc.xhscdn.com/202610061338/h2/AAAA0002!h5_1080jpg"},
            {"fileId":"AAAA0003","url":"http://sns-webpic-qc.xhscdn.com/202610061338/h3/AAAA0003!h5_1080jpg"}],
            "title":"今天穿这套去约会怎么样？"}}
        """.trimIndent()
        val html = "<html><head><title> - 小红书</title></head><body><script>$state</script></body></html>"
        val http = FakeHttp { url ->
            if (url == "https://www.xiaohongshu.com/") {
                HttpResponse(200, "home", headersOf("Set-Cookie", "acw_tc=s;path=/"), "")
            } else ok(html)
        }
        val result = parser.parse(
            "https://www.xiaohongshu.com/discovery/item/6ac3c5c4000000001500ef53?xsec_token=T",
            testContext(http, cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc"))
        )
        val images = result.media.filter { it.kind.name == "IMAGE" }
        assertEquals(3, images.size)
        assertEquals(3, images.map { it.url }.distinct().size)
        assertEquals("今天穿这套去约会怎么样？", result.title)
    }

    /**
     * 视频帖真实结构（2026-10 用户分享链接 `xhslink.cn/o/22TlIMvD96D` → `type=video`，
     * 主机抓取取证）：`video.media.stream.h264[].masterUrl` + `backupUrls`，
     * 封面在 `imageList[0].infoList[]`（H5_DTL/H5_PRV 两形态、**同一 fileId**），
     * 标题就是旗子 emoji `🇲🇴`，作者在 `user.nickName`。
     * 断言：只出视频、封面图不落媒体、判为单选（走自动下载）、标题作者正确。
     */
    @Test
    fun `视频帖 只出视频且封面图不落媒体`() {
        val fixture = fixture("xhs_video_note.html")
        val http = FakeHttp { url ->
            if (url == "https://www.xiaohongshu.com/") {
                HttpResponse(200, "home", headersOf("Set-Cookie", "acw_tc=s;path=/"), "")
            } else ok(fixture)
        }
        val result = parser.parse(
            "https://www.xiaohongshu.com/discovery/item/6ac05cc4000000001b02d811?type=video&xsec_token=T",
            testContext(http, cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc"))
        )
        val videos = result.media.filter { it.kind.name == "VIDEO" }
        val images = result.media.filter { it.kind.name == "IMAGE" }
        assertEquals("应提取到 1 个视频: ${result.media.map { it.url }}", 1, videos.size)
        assertTrue("封面帧不应作为独立媒体: ${images.map { it.url }}", images.isEmpty())
        assertTrue("视频帖应判为单选（走自动下载）", !result.isAlbumMultiSelect)
        assertTrue("视频 URL 应指向 xhscdn: ${videos[0].url}", videos[0].url.contains("xhscdn.com"))
        assertEquals("🇲🇴", result.title)
        assertEquals("菠萝烤狗", result.author)
    }

    @Test
    fun `视频笔记的 masterUrl 提取`() {
        val state = """
            window.__INITIAL_STATE__={"noteDetailMap":{"n1":{"note":{"video":{"media":{"stream":{"h264":[
            {"masterUrl":"https://sns-video.xhscdn.com/main.mp4","height":1080},
            {"masterUrl":"https://sns-video.xhscdn.com/backup.mp4","height":720}
            ]}}}}}}}
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

    /**
     * 实况动图（LivePhoto）混合笔记（如用户分享链接 `xhslink.cn/o/2LcOfnnTpoQ`）：
     * type 为 "normal"，imageList 包含 5 张图（第 1 张静态图，第 2~5 张带 stream.h264 实况视频）。
     * 必须提取全部 5 张原图 + 4 个实况动图视频，且判为多选图集（isAlbumMultiSelect = true）。
     */
    @Test
    fun `图文与实况动图混合笔记 完整提取所有图片与实况视频`() {
        val state = """
            window.__INITIAL_STATE__={"note":{"noteDetailMap":{"n1":{"note":{"type":"normal",
            "title":"实况图精选","user":{"nickName":"拍照达人"},
            "imageList":[
            {"fileId":"img0","livePhoto":false,"urlDefault":"http://sns-webpic.xhscdn.com/img0!nd_dft_wlteh_webp_3"},
            {"fileId":"img1","livePhoto":true,"urlDefault":"http://sns-webpic.xhscdn.com/img1!nd_dft_wlteh_webp_3","stream":{"h264":[{"masterUrl":"http://sns-video-v3.xhscdn.com/live1.mp4"}]}},
            {"fileId":"img2","livePhoto":true,"urlDefault":"http://sns-webpic.xhscdn.com/img2!nd_dft_wlteh_webp_3","stream":{"h264":[{"masterUrl":"http://sns-video-v3.xhscdn.com/live2.mp4"}]}},
            {"fileId":"img3","livePhoto":true,"urlDefault":"http://sns-webpic.xhscdn.com/img3!nd_dft_wlteh_webp_3","stream":{"h264":[{"masterUrl":"http://sns-video-v3.xhscdn.com/live3.mp4"}]}},
            {"fileId":"img4","livePhoto":true,"urlDefault":"http://sns-webpic.xhscdn.com/img4!nd_dft_wlteh_webp_3","stream":{"h264":[{"masterUrl":"http://sns-video-v3.xhscdn.com/live4.mp4"}]}}
            ]}}}}}
        """.trimIndent()
        val html = "<html><head><title> - 小红书</title></head><body><script>$state</script></body></html>"
        val http = FakeHttp { url ->
            if (url == "https://www.xiaohongshu.com/") {
                HttpResponse(200, "home", headersOf("Set-Cookie", "acw_tc=s;path=/"), "")
            } else ok(html)
        }
        val result = parser.parse(
            "https://www.xiaohongshu.com/discovery/item/6a8c382d0000000033010f28?xsec_token=T",
            testContext(http, cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc"))
        )
        val images = result.media.filter { it.kind.name == "IMAGE" }
        val liveVideos = result.media.filter { it.kind.name == "VIDEO" }
        assertEquals("应提取全部 5 张原图", 5, images.size)
        assertEquals("应提取 4 个实况动图视频", 4, liveVideos.size)
        assertEquals("共 9 个媒体项", 9, result.media.size)
        assertTrue("多图图集必须触发多选图集模式", result.isAlbumMultiSelect)
        assertEquals("实况图精选", result.title)
        assertEquals("拍照达人", result.author)
    }
}

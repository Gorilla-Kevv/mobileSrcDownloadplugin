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

    @Test
    fun `敏感内容推文给出明确文案`() {
        // 敏感内容推文：公开接口 200 返回但不下发媒体（2026-10 实测），entities.media 只剩 t.co
        val body = """{"text":"x","possibly_sensitive":true,"entities":{"media":[{"url":"https://t.co/xyz"}]},"user":{"name":"a"}}"""
        val http = FakeHttp { if (it.contains("syndication")) ok(body) else notFound() }
        val err = runCatching {
            parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))
        }.exceptionOrNull()
        assertTrue("应提示敏感内容: ${err?.message}", err!!.message!!.contains("敏感内容"))
    }

    @Test
    fun `敏感内容推文 走 fxTwitter 镜像兜底拿到原图`() {
        // syndication 对敏感内容推文 200 但不给媒体 → 兜底镜像返回 pbs 直链
        val tweetResult = """{"text":"x","possibly_sensitive":true,"entities":{"media":[{"url":"https://t.co/xyz"}]},"user":{"name":"a"}}"""
        val fx = """
            {"code":200,"message":"OK","tweet":{"id":"2094077925989515691","author":{"screen_name":"a"},
             "media":{"all":[{"type":"photo","url":"https://pbs.twimg.com/media/HUHaKxjW4AAKpQt.jpg?name=orig"}]}}}
        """.trimIndent()
        val http = FakeHttp { req ->
            when {
                req.contains("syndication") -> ok(tweetResult)
                req.contains("fxtwitter") -> ok(fx)
                else -> notFound()
            }
        }
        val r = parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))
        val img = r.media.single()
        assertEquals("IMAGE", img.kind.name)
        assertTrue("应拿镜像直链: ${img.url}", img.url.startsWith("https://pbs.twimg.com/media/"))
        assertTrue("应带兜底说明: ${r.warning}", r.warning?.contains("fxTwitter") == true)
    }

    @Test
    fun `解析真实 syndication 图片推文 提取原图`() {
        // photos[].url → pbs.twimg.com 直链（photo_info 已被上游移除）
        val body = """
            {"text":"photo tweet","user":{"name":"NASA"},
             "photos":[{"url":"https://pbs.twimg.com/media/HUHaKxjW4AAKpQt.jpg","width":3072,"height":4096}]}
        """.trimIndent()
        val http = FakeHttp { if (it.contains("syndication")) ok(body) else notFound() }
        val r = parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))
        val img = r.media.single()
        assertEquals("IMAGE", img.kind.name)
        assertTrue("应拼接 orig 原图参数: ${img.url}", img.url.endsWith("?format=jpg&name=orig"))
    }

    // ── 阶段 85：混合推文（图+视频）必须两类都拿到 ─────────────────────────────

    /**
     * 用户反馈的核心场景：推文同时有视频和图片，但 syndication 只下发 photos[]，
     * 不下发 video_info.variants。旧实现因为 `media.isEmpty()` 为 false 而跳过兜底，
     * 导致视频永远丢失。现在必须走 fxTwitter 把视频补回来。
     */
    @Test
    fun `混合推文 syndication 只给图片时 由镜像补齐视频`() {
        val syndication = """
            {"text":"mixed tweet","user":{"name":"Mixed"},
             "entities":{"media":[{"url":"https://t.co/abc"}]},
             "photos":[{"url":"https://pbs.twimg.com/media/AAA.jpg"}]}
        """.trimIndent()
        val fx = """
            {"code":200,"tweet":{"id":"2094077925989515691",
             "media":{"all":[
               {"type":"photo","url":"https://pbs.twimg.com/media/AAA.jpg?name=orig"},
               {"type":"video","variants":[
                 {"url":"https://video.twimg.com/ext_tw_video/1/pu/vid/720x1280/high.mp4","bitrate":2176000},
                 {"url":"https://video.twimg.com/ext_tw_video/1/pu/vid/480x854/low.mp4","bitrate":632000}
               ]}
             ]}}}
        """.trimIndent()
        val http = FakeHttp { req ->
            when {
                req.contains("syndication") -> ok(syndication)
                req.contains("fxtwitter") -> ok(fx)
                else -> notFound()
            }
        }
        val r = parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))

        val videos = r.media.filter { it.kind.name == "VIDEO" }
        val images = r.media.filter { it.kind.name == "IMAGE" }
        assertTrue("混合推文必须解析出视频: ${r.media.map { it.kind }}", videos.isNotEmpty())
        assertTrue("混合推文必须保留图片", images.isNotEmpty())
        assertEquals("视频应有两个清晰度变体", 2, videos.size)
        assertTrue("应说明由镜像补齐: ${r.warning}", r.warning?.contains("补齐") == true)
    }

    /** 反向场景：syndication 只给视频、缺图片，也要由镜像补图 */
    @Test
    fun `混合推文 syndication 只给视频时 由镜像补齐图片`() {
        val syndication = """
            {"text":"mixed","user":{"name":"M"},
             "entities":{"media":[{"url":"https://t.co/abc"}]},
             "mediaDetails":[{"video_info":{"variants":[
               {"url":"https://video.twimg.com/a/high.mp4","content_type":"video/mp4","bitrate":2000000}
             ]}}]}
        """.trimIndent()
        val fx = """
            {"code":200,"tweet":{"media":{"all":[
               {"type":"video","variants":[{"url":"https://video.twimg.com/a/high.mp4","bitrate":2000000}]},
               {"type":"photo","url":"https://pbs.twimg.com/media/BBB.jpg"}
            ]}}}
        """.trimIndent()
        val http = FakeHttp { req ->
            when {
                req.contains("syndication") -> ok(syndication)
                req.contains("fxtwitter") -> ok(fx)
                else -> notFound()
            }
        }
        val r = parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))
        assertTrue(r.media.any { it.kind.name == "VIDEO" })
        assertTrue("缺图片时应由镜像补图", r.media.any { it.kind.name == "IMAGE" })
    }

    /** 镜像把同一张图再给一遍时不能产生重复条目 */
    @Test
    fun `镜像补齐时按 URL 去重 不产生重复图片`() {
        val syndication = """
            {"text":"m","user":{"name":"M"},
             "entities":{"media":[{"url":"https://t.co/x"}]},
             "photos":[{"url":"https://pbs.twimg.com/media/SAME.jpg"}]}
        """.trimIndent()
        val fx = """
            {"code":200,"tweet":{"media":{"all":[
               {"type":"photo","url":"https://pbs.twimg.com/media/SAME.jpg?name=orig"},
               {"type":"photo","url":"https://pbs.twimg.com/media/SAME.jpg"},
               {"type":"video","variants":[{"url":"https://video.twimg.com/v.mp4","bitrate":1000000}]}
            ]}}}
        """.trimIndent()
        val http = FakeHttp { req ->
            when {
                req.contains("syndication") -> ok(syndication)
                req.contains("fxtwitter") -> ok(fx)
                else -> notFound()
            }
        }
        val r = parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))
        val images = r.media.filter { it.kind.name == "IMAGE" }
        assertEquals("同一张图只能出现一次", 1, images.size)
        assertEquals("视频应被补进来", 1, r.media.count { it.kind.name == "VIDEO" })
    }

    /** 两类都齐全时不应再请求镜像（避免多余网络开销） */
    @Test
    fun `两类媒体齐全时不再请求镜像`() {
        val syndication = """
            {"text":"full","user":{"name":"F"},
             "entities":{"media":[{"url":"https://t.co/x"}]},
             "mediaDetails":[{"video_info":{"variants":[
               {"url":"https://video.twimg.com/a.mp4","content_type":"video/mp4","bitrate":1000000}
             ]}}],
             "photos":[{"url":"https://pbs.twimg.com/media/C.jpg"}]}
        """.trimIndent()
        var fxCalled = false
        val http = FakeHttp { req ->
            when {
                req.contains("syndication") -> ok(syndication)
                req.contains("fxtwitter") -> { fxCalled = true; notFound() }
                else -> notFound()
            }
        }
        val r = parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))
        assertTrue(r.media.any { it.kind.name == "VIDEO" })
        assertTrue(r.media.any { it.kind.name == "IMAGE" })
        assertTrue("齐全时不应再打镜像", !fxCalled)
    }

    // ── 阶段 86：GIF 必须能下载 ────────────────────────────────────────────────

    /**
     * 用户反馈「gif 不会下载」。
     *
     * X 的 GIF 有两种下发形态：
     * 1. `variants` 里同时给 `.mp4`（转码后）与 `.gif`（原始动图）；
     * 2. 只给 `.gif`。
     *
     * 旧实现用 `filter { url.contains(".mp4") }` 硬过滤，只给 `.gif` 时
     * 变体列表被清空 → 该 GIF 整条丢失，表现为「gif 下载不了」。
     */
    @Test
    fun `GIF 只有 gif 原文件时也要解析出来`() {
        val fx = """
            {"code":200,"tweet":{"media":{"all":[
               {"type":"gif","url":"https://pbs.twimg.com/tweet_video/ABC.gif",
                "variants":[{"url":"https://video.twimg.com/tweet_video/ABC.gif","bitrate":0}]}
            ]}}}
        """.trimIndent()
        val http = FakeHttp { req ->
            when {
                req.contains("syndication") -> ok("""{"text":"gif","user":{"name":"G"}}""")
                req.contains("fxtwitter") -> ok(fx)
                else -> notFound()
            }
        }
        val r = parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))
        assertTrue("GIF 必须被解析出来: ${r.media.map { it.url }}", r.media.isNotEmpty())
        val gif = r.media.first()
        assertTrue("应保留 .gif 直链: ${gif.url}", gif.url.contains(".gif"))
        assertEquals("gif", gif.container)
    }

    /** GIF 同时给 mp4 与 gif 时，优先 mp4（体积更小、相册可直接播），但 gif 作为备选保留 */
    @Test
    fun `GIF 同时有 mp4 与 gif 时两者都保留且 mp4 优先`() {
        val fx = """
            {"code":200,"tweet":{"media":{"all":[
               {"type":"gif","variants":[
                 {"url":"https://video.twimg.com/tweet_video/ABC.mp4","bitrate":0},
                 {"url":"https://video.twimg.com/tweet_video/ABC.gif","bitrate":0}
               ]}
            ]}}}
        """.trimIndent()
        val http = FakeHttp { req ->
            when {
                req.contains("syndication") -> ok("""{"text":"gif","user":{"name":"G"}}""")
                req.contains("fxtwitter") -> ok(fx)
                else -> notFound()
            }
        }
        val r = parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))
        assertEquals("两种形态都要保留", 2, r.media.size)
        assertTrue("mp4 应排名更高（优先）", r.media.first().url.contains(".mp4"))
        assertTrue("gif 作为备选保留", r.media.any { it.url.contains(".gif") })
    }

    /** GIF 的 MIME 必须标成 image/gif（否则会被当成视频写入 Movies/ 且播放器打不开） */
    @Test
    fun `GIF 的 mimeType 是 image gif 而不是 video mp4`() {
        val fx = """
            {"code":200,"tweet":{"media":{"all":[
               {"type":"gif","variants":[{"url":"https://video.twimg.com/tweet_video/ABC.gif","bitrate":0}]}
            ]}}}
        """.trimIndent()
        val http = FakeHttp { req ->
            when {
                req.contains("syndication") -> ok("""{"text":"gif","user":{"name":"G"}}""")
                req.contains("fxtwitter") -> ok(fx)
                else -> notFound()
            }
        }
        val r = parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))
        val gif = r.media.first { it.url.contains(".gif") }
        assertEquals("image/gif", gif.mimeType)
    }

    /** 主通道 syndication 的 content_type 可能是 image/gif，同样不能被过滤 */
    @Test
    fun `主通道 content_type 为 image gif 时也能解析出 GIF`() {
        val syndication = """
            {"text":"gif","user":{"name":"G"},
             "mediaDetails":[{"video_info":{"variants":[
               {"url":"https://video.twimg.com/tweet_video/XYZ.gif","content_type":"image/gif","bitrate":0}
             ]}}]}
        """.trimIndent()
        val http = FakeHttp { req ->
            when {
                req.contains("syndication") -> ok(syndication)
                else -> notFound()
            }
        }
        val r = parser.parse("https://x.com/a/status/2094077925989515691", testContext(http))
        val gif = r.media.single()
        assertEquals("image/gif", gif.mimeType)
        assertEquals("gif", gif.container)
    }
}

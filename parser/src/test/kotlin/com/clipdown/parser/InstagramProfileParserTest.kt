package com.clipdown.parser

import com.clipdown.parser.http.HttpResponse
import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.Platform
import com.clipdown.parser.model.PostKind
import com.clipdown.parser.parsers.InstagramParser
import com.clipdown.parser.parsers.InstagramProfileParser
import com.clipdown.parser.parsers.IgRiskGuard
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Instagram 博主主页解析。
 *
 * 夹具是**结构样本**（按 IG web_profile_info 与主页 `og:*` 的公开字段手工构造）：
 * IG 主页免登录只会返回登录墙（2026-10 实测：200/637KB、无 og 标签、无帖子链接），
 * 真实响应需在设置页注入 Cookie 后于设备上校准。
 */
class InstagramProfileParserTest {

    private val parser = InstagramProfileParser()
    private val url = "https://www.instagram.com/nasa/"
    private val cookie = "sessionid=abc; ds_user_id=1234567890; csrftoken=xyz"

    @Before
    fun resetFuse() {
        IgRiskGuard.resetForTest()
    }

    private fun ctxWith(
        responses: Map<String, String> = emptyMap(),
        code: Int = 200,
        rendered: String? = null,
        counter: IntArray? = null
    ) = testContext(
        FakeHttp { req ->
            if (counter != null) counter[0]++
            val key = responses.keys.firstOrNull { req.contains(it) }
            HttpResponse(code, key?.let { responses[it] }, Headers.headersOf(), "")
        },
        cookies = mapOf(Platform.INSTAGRAM to cookie),
        webFetcher = rendered?.let { { _: String -> it } }
    )

    @Test
    fun `无登录态给出可操作文案且不发起请求`() {
        val calls = IntArray(1)
        val ctx = testContext(
            FakeHttp { calls[0]++; ok("{}") },
            cookies = emptyMap()
        )
        val err = runCatching { parser.parseProfile(url, "nasa", ctx) }.exceptionOrNull()
        assertTrue(err is ParseException)
        assertTrue("文案应指向设置页: ${err?.message}", err!!.message!!.contains("设置"))
        assertEquals("缺 Cookie 时不应发请求", 0, calls[0])
        assertEquals("缺 Cookie 不应计入风控失败", 0, IgRiskGuard.remainingMinutes)
    }

    @Test
    fun `接口通道给出完整字段`() {
        val r = parser.parseProfile(
            url, "nasa",
            ctxWith(mapOf("web_profile_info" to fixture("ig_profile_api.json")))
        )
        assertEquals("ig-profile-v1", r.resolverId)
        assertEquals("NASA", r.nickname)
        assertEquals(80400123, r.stats?.followers)
        assertEquals(120, r.stats?.following)
        assertEquals(4562, r.stats?.posts)
        assertTrue("简介应保留换行前的首行内容: ${r.bio}", r.bio!!.contains("Exploring the unknown"))
        assertTrue("头像取高清档: ${r.avatar}", r.avatar!!.contains("profile") || r.avatar!!.startsWith("https://"))

        assertEquals(3, r.posts.size)
        val video = r.posts.first { it.id == "CvVideo1" }
        assertEquals(PostKind.VIDEO, video.kind)
        assertEquals("https://www.instagram.com/reel/CvVideo1/", video.url)
        assertEquals("Liftoff!", video.title)   // 只取首行
        assertEquals(213456, video.likedCount)

        val album = r.posts.first { it.id == "CvAlbum2" }
        assertEquals(PostKind.ALBUM, album.kind)
        assertEquals(3, album.mediaCount)
        assertNullLike(album.title)   // 正文只有空白 → 视为无标题

        val photo = r.posts.first { it.id == "CvPhoto3" }
        assertEquals(PostKind.UNKNOWN, photo.kind)
        assertEquals(1234567, photo.likedCount)
        assertTrue("封面应为 display_url: ${photo.cover}", photo.cover!!.startsWith("https://"))

        assertFalse("公开接口无游标", r.hasMore)
    }

    /** 接口挂了要能退到页面通道，并把 "80.4M" 这类紧凑计数换算对 */
    @Test
    fun `接口失败时退到页面通道`() {
        val r = parser.parseProfile(
            url, "nasa",
            ctxWith(
                mapOf(
                    "web_profile_info" to "",                       // 接口空响应
                    "instagram.com/nasa" to fixture("ig_profile_page.html")
                )
            )
        )
        assertEquals("NASA", r.nickname)
        assertEquals(80400000, r.stats?.followers)
        assertEquals(120, r.stats?.following)
        assertEquals(4562, r.stats?.posts)
        assertEquals("应扫到 3 篇（重复链接去重）: ${r.posts.map { it.id }}", 3, r.posts.size)
        assertEquals(PostKind.VIDEO, r.posts.first { it.id == "CvVideo1" }.kind)
        assertTrue("相对路径封面要补全: ${r.posts.map { it.cover }}",
            r.posts.first { it.id == "CvAlbum2" }.cover!!.startsWith("https://"))
        assertTrue("页面通道应给出提示: ${r.warning}", (r.warning ?: "").contains("链接"))
    }

    @Test
    fun `三通道全失败时提示风控与冷却`() {
        val err = runCatching {
            parser.parseProfile(url, "nasa", ctxWith(mapOf("nothing" to "<html>login</html>"), code = 429))
        }.exceptionOrNull()
        assertTrue("应抛业务异常: $err", err is ParseException)
        val msg = err!!.message!!
        assertTrue("文案应说明已试过通道: $msg", msg.contains("api") && msg.contains("html"))
        assertTrue("文案应提示不要连续重试: $msg", msg.contains("冷却") || msg.contains("风控"))
    }

    /** 风控纪律：连续失败两次进入冷却，期间连一个请求都不发（修复 11 的延伸——与单篇链路共享计数） */
    @Test
    fun `连续失败两次后进入冷却且零请求`() {
        val calls = IntArray(1)
        val failing = ctxWith(mapOf("nothing" to ""), code = 429, counter = calls)

        repeat(2) {
            assertTrue("第 1/2 次应解析失败", runCatching { parser.parseProfile(url, "nasa", failing) }.isFailure)
        }
        // 每轮会依次试 api + html 两个通道
        assertEquals("前两轮应各发两个通道的请求", 4, calls[0])
        assertTrue("应已进入冷却", IgRiskGuard.remainingMinutes > 0)

        val err = runCatching { parser.parseProfile(url, "nasa", failing) }.exceptionOrNull()
        assertTrue("冷却期应直接抛出: ${err?.message}", (err?.message ?: "").contains("冷却"))
        assertEquals("冷却期内不得再发请求", 4, calls[0])
    }

    /** 主页失败两次就会把单篇链路一起冷却——这正是共享保险丝要防的"绕道轰炸" */
    @Test
    fun `主页失败会连带单篇链路进入冷却`() {
        val failing = ctxWith(mapOf("nothing" to ""), code = 429)
        repeat(2) { runCatching { parser.parseProfile(url, "nasa", failing) } }

        val calls = IntArray(1)
        val postCtx = testContext(
            FakeHttp { calls[0]++; ok("{}") },
            cookies = mapOf(Platform.INSTAGRAM to cookie)
        )
        val err = runCatching {
            InstagramParser().parse("https://www.instagram.com/p/CvPhoto3/", postCtx)
        }.exceptionOrNull()
        assertTrue("单篇也应被冷却拦住: ${err?.message}", (err?.message ?: "").contains("冷却"))
        assertEquals("被拦住时不得发请求", 0, calls[0])
    }

    private fun assertNullLike(v: String?) {
        assertTrue("应为空标题: $v", v.isNullOrBlank())
    }
}

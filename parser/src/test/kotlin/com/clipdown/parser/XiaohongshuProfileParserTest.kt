package com.clipdown.parser

import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.Platform
import com.clipdown.parser.model.PostKind
import com.clipdown.parser.parsers.XiaohongshuProfileParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小红书主页解析（DOM 提取路径）。
 *
 * 主页笔记列表不是 SSR，而是页面 JS 带签名 XHR 拉取后渲染进 DOM；
 * 所以这里针对**渲染后的 DOM**做提取，夹具即一份渲染结果快照。
 */
class XiaohongshuProfileParserTest {

    private val parser = XiaohongshuProfileParser()
    private val url = "https://www.xiaohongshu.com/user/profile/67c01c94000000000d00ada2"

    @Test
    fun `从渲染后的 DOM 提取笔记列表与博主信息`() {
        val fixture = fixture("xhs_profile.html")
        val logs = mutableListOf<String>()
        val ctx = testContext(
            FakeHttp { ok(fixture) },
            cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc"),
            logs = logs,
            webFetcher = { fixture }
        )

        val result = parser.parseProfile(url, "67c01c94000000000d00ada2", ctx)

        // 同一篇笔记的 /explore 与 /discovery/item 两种链接形态必须按 id 去重
        assertEquals("笔记数（应去重）: ${result.posts.map { it.id }}", 2, result.posts.size)
        assertEquals("6ac3c5c4000000001500ef52", result.posts[0].id)
        assertEquals("搬一下之前画过的小樱", result.posts[0].title)
        assertTrue("应带封面: ${result.posts[0].cover}", result.posts[0].cover!!.contains("xhscdn"))
        assertEquals("笔记页链接应可点开", "https://www.xiaohongshu.com/explore/6ac3c5c4000000001500ef52", result.posts[0].url)

        assertEquals("折纸", result.nickname)
        assertNotNull(result.avatar)
        assertEquals("画点小樱，偶尔发发 cos，更新很慢", result.bio)
        assertEquals(12000, result.stats?.followers)
        assertEquals(3456, result.stats?.likes)
        assertEquals(42, result.stats?.posts)
        assertEquals(128, result.stats?.following)
        assertEquals("xhs-profile-v1", result.resolverId)
    }

    /** WebView 不可用时退到移动端 UA 直连 */
    @Test
    fun `WebView 不可用时退到直连`() {
        val fixture = fixture("xhs_profile.html")
        val logs = mutableListOf<String>()
        val ctx = testContext(
            FakeHttp { ok(fixture) },
            cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc"),
            logs = logs
        )
        val result = parser.parseProfile(url, "u1", ctx)
        assertEquals(2, result.posts.size)
        assertTrue("日志应记录走的是直连: $logs", logs.any { it.contains("via=okhttp") })
    }

    /** 登录墙：页面被跳到首页/登录页时必须给出可操作文案，不能把首页当主页返回 */
    @Test
    fun `登录墙给出补 Cookie 的文案`() {
        val wall = "<html><head><title>小红书 - 你的生活兴趣社区</title></head><body></body></html>"
        val ctx = testContext(FakeHttp { ok(wall) }, webFetcher = { wall })
        val err = runCatching { parser.parseProfile(url, "u1", ctx) }.exceptionOrNull()
        assertTrue("应抛业务异常: $err", err is ParseException)
        assertTrue("文案应提示补 Cookie: ${err?.message}", err!!.message!!.contains("Cookie"))
    }

    /** 没有笔记卡片时明确失败，而不是返回空主页 */
    @Test
    fun `无笔记卡片时明确报错`() {
        val empty = "<html><head><title>折纸 - 小红书</title></head><body><div>加载中</div></body></html>"
        val ctx = testContext(FakeHttp { ok(empty) }, webFetcher = { empty })
        val err = runCatching { parser.parseProfile(url, "u1", ctx) }.exceptionOrNull()
        assertTrue(err is ParseException)
        assertTrue("文案应说明原因: ${err?.message}", err!!.message!!.contains("笔记列表"))
    }

    @Test
    fun `统计数字换算 万 亿 千分位`() {
        assertEquals(12000, parser.parseCount("1.2万"))
        assertEquals(3456, parser.parseCount("3,456"))
        assertEquals(12, parser.parseCount("12"))
        assertEquals(100000000, parser.parseCount("1亿"))
        assertEquals(150000000, parser.parseCount("1.5亿"))
        assertEquals(null, parser.parseCount(""))
    }
}

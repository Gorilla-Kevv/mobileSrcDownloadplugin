package com.clipdown.parser

import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.Platform
import com.clipdown.parser.model.PostKind
import com.clipdown.parser.parsers.XiaohongshuProfileParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小红书主页解析（SSR 提取）。
 *
 * 夹具是**真实抓取裁剪**：桌面 UA + 登录态 Cookie 访问
 * `https://www.xiaohongshu.com/user/profile/<userId>` 得到的 `__INITIAL_STATE__` 片段
 * （2026-10 实测：桌面 UA 才有 SSR，移动端 UA 只返回客户端渲染外壳）。
 */
class XiaohongshuProfileParserTest {

    private val parser = XiaohongshuProfileParser()
    private val url = "https://www.xiaohongshu.com/user/profile/561d336a33f60c555d600dec"

    @Test
    fun `从 SSR 提取博主信息与笔记列表`() {
        val fixture = fixture("xhs_profile.html")
        val logs = mutableListOf<String>()
        val ctx = testContext(
            FakeHttp { ok(fixture) },
            cookies = mapOf(Platform.XIAOHONGSHU to "a1=abc; web_session=xyz"),
            logs = logs
        )

        val result = parser.parseProfile(url, "561d336a33f60c555d600dec", ctx)

        assertEquals(2, result.posts.size)
        // 昵称/统计
        assertEquals("lindazq", result.nickname)
        assertEquals(3293, result.stats?.followers)
        assertEquals(36, result.stats?.following)
        assertEquals(485979, result.stats?.likes)
        assertEquals(4127, result.stats?.posts)
        // 简介是"还没有简介"时视为无简介，避免把占位文案当内容展示
        assertNull(result.bio)
        assertTrue("头像应取 images: ${result.avatar}", result.avatar!!.contains("sns-avatar"))
        assertEquals("xhs-profile-v1", result.resolverId)
        assertTrue("日志应记录走的是桌面 UA: $logs", logs.any { it.contains("via=desktop") })
    }

    /** 每篇的 URL 必须带 SSR 里的 xsecToken——裸 /explore/<id> 会被判无效链接 */
    @Test
    fun `笔记 URL 带上 xsecToken`() {
        val fixture = fixture("xhs_profile.html")
        val ctx = testContext(
            FakeHttp { ok(fixture) },
            cookies = mapOf(Platform.XIAOHONGSHU to "web_session=xyz")
        )
        val result = parser.parseProfile(url, "u1", ctx)
        val first = result.posts[0]
        assertEquals("689cb299000000001d00e681", first.id)
        assertTrue("URL 应含 xsec_token: ${first.url}", first.url.contains("xsec_token="))
        assertTrue("URL 应指向 explore: ${first.url}", first.url.contains("/explore/689cb299000000001d00e681"))
        assertEquals("莱昂纳多·迪卡普里奥首度坦承\"毕生最大遗憾\"：30年前拒绝《不羁夜》邀约", first.title)
        // 封面优先取 urlDefault（WB_DFT，默认画质）
        assertTrue("封面应取 urlDefault: ${first.cover}", first.cover!!.contains("nc_n_nwebp_mw_1"))
        assertEquals(2268, first.likedCount)
        assertEquals(PostKind.UNKNOWN, first.kind)
    }

    /** type=video 的笔记要判为视频（列表页据此显示角标、用户据此筛选） */
    @Test
    fun `视频笔记判为 VIDEO`() {
        val fixture = fixture("xhs_profile.html")
        val ctx = testContext(FakeHttp { ok(fixture) }, cookies = mapOf(Platform.XIAOHONGSHU to "s=1"))
        val result = parser.parseProfile(url, "u1", ctx)
        val video = result.posts.first { it.id == "6ac05cc4000000001b02d811" }
        assertEquals(PostKind.VIDEO, video.kind)
        assertEquals("今天穿这套去约会怎么样？", video.title)
    }

    /** 登录墙：页面被跳到首页/登录页时必须给出可操作文案 */
    @Test
    fun `登录墙给出补 Cookie 的文案`() {
        val wall = "<html><head><title>小红书 - 你的生活兴趣社区</title></head><body></body></html>"
        val ctx = testContext(FakeHttp { ok(wall) }, webFetcher = { wall })
        val err = runCatching { parser.parseProfile(url, "u1", ctx) }.exceptionOrNull()
        assertTrue("应抛业务异常: $err", err is ParseException)
        assertTrue("文案应提示补 Cookie: ${err?.message}", err!!.message!!.contains("Cookie"))
    }

    /** 有页面但没笔记卡片（如空账号）时明确失败，而不是返回空主页 */
    @Test
    fun `无笔记卡片时明确报错`() {
        val empty = "<html><head><title>lindazq - 小红书</title></head><body>" +
            "<script>window.__INITIAL_STATE__={\"user\":{\"userPageData\":{\"basicInfo\":{\"nickname\":\"lindazq\"},\"notes\":[]}}}</script></body></html>"
        val ctx = testContext(FakeHttp { ok(empty) }, webFetcher = { empty })
        val err = runCatching { parser.parseProfile(url, "u1", ctx) }.exceptionOrNull()
        assertTrue(err is ParseException)
        assertTrue("文案应说明原因: ${err?.message}", err!!.message!!.contains("笔记列表"))
    }

    /**
     * 加载更多：优先消费 WebView 钩子回收的 `user_posted` 分页响应。
     *
     * 夹具是**真机取证**（2026-10）：无窗口 WebView 里页面虚拟列表即使拿到第 2/3 页
     * 响应也不再渲染新卡片，DOM 提取恒为 0，只能直接吃接口 JSON。
     * 回传格式 = `渲染后的 DOM + "<<<XHS_PAGES>>>" + JSON.stringify(响应原文数组)`。
     */
    @Test
    fun `加载更多消费分页接口响应并按 id 去重合并`() {
        val fixture = fixture("xhs_profile.html")
        val pages = fixture("xhs_profile_pages.json")
        val logs = mutableListOf<String>()
        var scrolled: Triple<String, Int, Boolean>? = null
        val ctx = testContext(
            FakeHttp { ok(fixture) },
            cookies = mapOf(Platform.XIAOHONGSHU to "web_session=xyz"),
            logs = logs,
            webFetcherScroll = { u, times, desktop ->
                scrolled = Triple(u, times, desktop)
                fixture + "<<<XHS_PAGES>>>" + pages
            }
        )

        val first = parser.parseProfile(url, "u1", ctx)          // pages=1 → 不翻页
        assertNull("首屏不应触发滚动抓取", scrolled)

        val result = parser.parseProfile(url, "u1", ctx, pages = 2)
        assertEquals("翻页应请求桌面 UA 且滚多屏: $scrolled", true, scrolled?.third)
        assertEquals(3, scrolled?.second)
        assertTrue("应合并接口分页数据: $logs", logs.any { it.contains("加载更多：接口=") && !it.contains("接口=0") })
        assertTrue(
            "总数应超过首屏 2 篇: ${result.posts.size}",
            result.posts.size > first.posts.size
        )
        assertEquals("按 id 去重后不应有重复", result.posts.size, result.posts.distinctBy { it.id }.size)

        val added = result.posts.first { it.id == "6ac3a528000000001b02c389" }
        assertEquals("奥斯卡获奖短片导演首部长片敲定卡司，杰克・奥康奈尔搭档敖德萨・阿锡安开启荒诞公路", added.title)
        assertTrue("URL 必须带 xsec_token: ${added.url}", added.url.contains("xsec_token="))
        assertTrue("token 里的 = 必须转义: ${added.url}", added.url.contains("%3D"))
        assertEquals(4, added.likedCount)
        assertEquals(1791208560000L, added.publishedAt)
        assertTrue("封面取 url_default: ${added.cover}", added.cover!!.startsWith("http"))
    }
}

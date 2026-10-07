package com.clipdown.parser

import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.PostKind
import com.clipdown.parser.parsers.XProfileParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * X 博主主页解析（公开 syndication 时间线，免登录）。
 *
 * 夹具是**真实抓取裁剪**：`https://syndication.twitter.com/srv/timeline-profile/screen-name/elonmusk`
 * 的 `__NEXT_DATA__`，保留视频/单图/纯文本/引用四类代表性推文（2026-10）。
 */
class XProfileParserTest {

    private val parser = XProfileParser()
    private val url = "https://x.com/elonmusk"

    private fun parse() = parser.parseProfile(
        url, "elonmusk", testContext(FakeHttp { ok(fixture("x_profile_timeline.html")) })
    )

    @Test
    fun `提取博主信息与统计`() {
        val r = parse()
        assertEquals("x-syndication-profile-v1", r.resolverId)
        assertEquals("Elon Musk", r.nickname)
        assertEquals(241704401, r.stats?.followers)
        assertEquals(1415, r.stats?.following)
        assertEquals(109588, r.stats?.posts)
        // 头像要换成 400px 档，_normal 只有 48px，信息卡上会糊
        assertTrue("头像应为高清档: ${r.avatar}", r.avatar!!.contains("_400x400."))
        assertFalse("应取到简介", r.bio.isNullOrBlank())
    }

    @Test
    fun `推文映射为笔记条目`() {
        val r = parse()
        assertTrue("应有推文: ${r.posts.size}", r.posts.isNotEmpty())
        assertEquals("按 id 去重", r.posts.size, r.posts.distinctBy { it.id }.size)

        val video = r.posts.first { it.kind == PostKind.VIDEO }
        assertTrue("链接应指向状态页: ${video.url}", video.url.startsWith("https://x.com/elonmusk/status/"))
        assertTrue("视频条也应有封面（缩略图）: ${video.cover}", video.cover!!.startsWith("https://"))
        assertEquals(1, video.mediaCount)
        assertTrue("点赞数应解析出来: ${video.likedCount}", (video.likedCount ?: 0) > 0)
        // created_at = "Sat Jul 13 22:45:13 +0000 2024"
        assertEquals(1720910713000L, video.publishedAt)

        val photo = r.posts.first { it.kind == PostKind.IMAGE }
        assertTrue("图片封面应为 pbs.twimg.com: ${photo.cover}", photo.cover!!.contains("pbs.twimg.com"))

        val textOnly = r.posts.first { it.kind == PostKind.UNKNOWN }
        assertNull("纯文本条不该有封面", textOnly.cover)
        assertNull("纯文本条 mediaCount 应为空", textOnly.mediaCount)
    }

    /** 正文结尾的 t.co 分享短链不是内容，展示时要剥掉 */
    @Test
    fun `标题剥掉结尾分享短链`() {
        val r = parse()
        assertTrue(
            "标题不应以 t.co 结尾: ${r.posts.map { it.title }}",
            r.posts.all { !(it.title ?: "").endsWith("t.co") && !(it.title ?: "").contains("https://t.co") }
        )
    }

    /** 公开时间线没有游标：hasMore 必须为 false，否则 UI 会挂一个永远点不动的按钮 */
    @Test
    fun `无分页游标时 hasMore 为 false`() {
        assertFalse(parse().hasMore)
    }

    @Test
    fun `空时间线给出可操作文案`() {
        val empty = """<html><body><script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"timeline":{"entries":[]}}}}</script></body></html>"""
        val err = runCatching {
            parser.parseProfile(url, "someone", testContext(FakeHttp { ok(empty) }))
        }.exceptionOrNull()
        assertTrue("应抛业务异常: $err", err is ParseException)
        assertTrue("文案应说明原因: ${err?.message}", err!!.message!!.contains("公开时间线"))
    }

    @Test
    fun `页面结构缺失时明确失败`() {
        val err = runCatching {
            parser.parseProfile(url, "someone", testContext(FakeHttp { ok("<html><body>login</body></html>") }))
        }.exceptionOrNull()
        assertTrue(err is ParseException)
        assertTrue("文案应提示账号问题: ${err?.message}", err!!.message!!.contains("不存在"))
    }
}

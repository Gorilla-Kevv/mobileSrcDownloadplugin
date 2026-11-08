package com.clipdown.parser

import com.clipdown.parser.core.UrlUtil
import com.clipdown.parser.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilTest {

    // ---- normalize ----

    @Test
    fun `normalize 补全协议头`() {
        assertEquals("https://x.com/a/status/1", UrlUtil.normalize("x.com/a/status/1"))
        assertEquals("https://b23.tv/abc", UrlUtil.normalize("b23.tv/abc"))
    }

    @Test
    fun `normalize 去尾部斜杠与跟踪参数`() {
        val out = UrlUtil.normalize("https://www.instagram.com/p/ABC123/?utm_source=share&igshid=xyz/")
        assertEquals("https://www.instagram.com/p/ABC123", out)
    }

    @Test
    fun `normalize 保留 xsec_token 功能参数`() {
        val out = UrlUtil.normalize(
            "https://www.xiaohongshu.com/explore/abc123?xsec_token=TOK&utm_source=share"
        )
        assertTrue("xsec_token 不应被剥除: $out", out.contains("xsec_token=TOK"))
        assertTrue("utm_source 应被剥除: $out", !out.contains("utm_source"))
    }

    @Test
    fun `normalize 去普通跟踪参数但保留业务参数`() {
        val out = UrlUtil.normalize("https://x.com/a/status/1?ref_src=twsrc%5Etfw&lang=zh-cn")
        assertTrue(out.contains("lang=zh-cn"))
        assertTrue(!out.contains("ref_src"))
    }

    // ---- extractFirst：query 必须完整召回（修复 8 前 URL_PATTERN 在半角 ? 处截断） ----

    @Test
    fun `extractFirst 保留 YouTube watch 的 v 参数`() {
        val out = UrlUtil.extractFirst("看这个 https://www.youtube.com/watch?v=KKo_yn2jSGY 很不错")
        assertEquals("https://www.youtube.com/watch?v=KKo_yn2jSGY", out)
    }

    @Test
    fun `extractFirst 保留小红书 xsec_token`() {
        val out = UrlUtil.extractFirst("https://www.xiaohongshu.com/explore/abc?xsec_token=TOK123&xsec_source=ss")
        assertEquals("https://www.xiaohongshu.com/explore/abc?xsec_token=TOK123&xsec_source=ss", out)
    }

    @Test
    fun `extractFirst 在全角问号与空白处正确截断`() {
        val out = UrlUtil.extractFirst("地址 https://x.com/a/status/1？看看这个")
        assertEquals("https://x.com/a/status/1", out)
    }

    // ---- extractFirst / extractAll ----

    @Test
    fun `从混排文本中召回链接`() {
        val text = "看看这个 https://x.com/astro/status/2094077925989515691 复制给朋友，还有 b23.tv/xyz 也行"
        val first = UrlUtil.extractFirst(text)
        assertTrue(first!!.startsWith("https://x.com/"))
        val all = UrlUtil.extractAll(text)
        assertEquals(2, all.size)
    }

    @Test
    fun `纯文本无链接返回 null`() {
        assertEquals(null, UrlUtil.extractFirst("今天天气不错"))
    }

    // ---- detectPlatform ----

    @Test
    fun `平台判定 主流域`() {
        assertEquals(Platform.X, UrlUtil.detectPlatform("https://x.com/a/status/1"))
        assertEquals(Platform.X, UrlUtil.detectPlatform("https://twitter.com/a/status/1"))
        assertEquals(Platform.BILIBILI, UrlUtil.detectPlatform("https://www.bilibili.com/video/BV1xx"))
        assertEquals(Platform.BILIBILI, UrlUtil.detectPlatform("https://b23.tv/abc"))
        assertEquals(Platform.INSTAGRAM, UrlUtil.detectPlatform("https://www.instagram.com/reel/ABC/"))
        assertEquals(Platform.XIAOHONGSHU, UrlUtil.detectPlatform("https://www.xiaohongshu.com/explore/abc"))
        assertEquals(Platform.YOUTUBE, UrlUtil.detectPlatform("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Platform.X, UrlUtil.detectPlatform("x.com/a/status/1"))
    }

    /**
     * App 分享文案里小红书短链下发的是 **xhslink.cn**（不是 .com），
     * 漏登记会让整条链接被判为"暂不支持该链接"。2026-10 用户实测链接取证。
     */
    @Test
    fun `平台判定 小红书 xhslink cn 短链`() {
        assertEquals(Platform.XIAOHONGSHU, UrlUtil.detectPlatform("https://xhslink.cn/o/7mDR2JlydL0"))
        assertEquals(Platform.XIAOHONGSHU, UrlUtil.detectPlatform("https://xhslink.com/m/abc"))
        assertTrue("xhslink.cn 应判定为短链", UrlUtil.isShortLink("https://xhslink.cn/o/7mDR2JlydL0"))
    }

    /** 真实分享文案：中文标题在前、链接居中、引导语在后 */
    @Test
    fun `extractFirst 从中文分享文案中召回链接`() {
        val text = "须臾的休憩 感谢大模@糖包Rohan https://xhslink.cn/o/7mDR2JlydL0\n戳【小红书】围观这篇内容！"
        assertEquals("https://xhslink.cn/o/7mDR2JlydL0", UrlUtil.extractFirst(text))
        val text2 = "今天穿这套去约会怎么样？ https://xhslink.cn/o/1kijoisLVUe\n复制一下，然后打开【小红书】就能看到啦！"
        assertEquals("https://xhslink.cn/o/1kijoisLVUe", UrlUtil.extractFirst(text2))
    }

    /** 分享文案可能以 emoji 旗子开头（小红书澳门帖实测），召回不应受影响 */
    @Test
    fun `extractFirst 从 emoji 开头的分享文案中召回链接`() {
        val text = "🇲🇴 https://xhslink.cn/o/22TlIMvD96D\n拷走文字，去【小红书】探索这篇笔记~"
        assertEquals("https://xhslink.cn/o/22TlIMvD96D", UrlUtil.extractFirst(text))
    }

    // ---- hostOf / pathOf ----

    @Test
    fun `host 与 path 提取`() {
        assertEquals("www.bilibili.com", UrlUtil.hostOf("https://www.bilibili.com/video/BV1xx?x=1"))
        assertEquals("/video/BV1xx", UrlUtil.pathOf("https://www.bilibili.com/video/BV1xx?x=1"))
    }

    // ---- dedupKey：已解析链接长期记忆的比对键 ----

    /**
     * 用户反馈的真实症状：同一条小红书笔记隔几天再复制一次，xsec_token 已经换了，
     * 旧实现用 normalize（保留 token）比对 → 永远匹配不上 → 不弹「已解析」提示。
     * dedupKey 必须收敛到笔记 ID，与 token 无关。
     */
    @Test
    fun `dedupKey 忽略 xsec_token 变化 收敛到笔记 ID`() {
        val a = UrlUtil.dedupKey(
            "https://www.xiaohongshu.com/explore/65f0a1b2c3d4e5f60718293a?xsec_token=AAA&xsec_source=pc_share"
        )
        val b = UrlUtil.dedupKey(
            "https://www.xiaohongshu.com/explore/65f0a1b2c3d4e5f60718293a?xsec_token=BBB&xsec_source=app_share"
        )
        assertEquals(a, b)
        assertEquals("xhs:note:65f0a1b2c3d4e5f60718293a", a)
    }

    /** 三种路径形态指向同一篇笔记时，去重键必须一致 */
    @Test
    fun `dedupKey 统一小红书三种笔记路径形态`() {
        val id = "65f0a1b2c3d4e5f60718293a"
        val explore = UrlUtil.dedupKey("https://www.xiaohongshu.com/explore/$id?xsec_token=T1")
        val discovery = UrlUtil.dedupKey("https://www.xiaohongshu.com/discovery/item/$id?xsec_token=T2")
        val notes = UrlUtil.dedupKey("https://www.xiaohongshu.com/notes/$id?xsec_token=T3")
        assertEquals(explore, discovery)
        assertEquals(explore, notes)
    }

    /** 非小红书链接仍然按「剥掉轮换参数后的完整 URL」比对，且保留业务参数 */
    @Test
    fun `dedupKey 其他平台的轮换签名参数也应被剥离`() {
        val out = UrlUtil.dedupKey("https://x.com/a/status/1?lang=zh-cn&sig=abc123&expires=1700000000")
        assertTrue("业务参数 lang 应保留: $out", out.contains("lang=zh-cn"))
        assertTrue("sig 应被剥离: $out", !out.contains("sig="))
        assertTrue("expires 应被剥离: $out", !out.contains("expires="))
    }

    /** normalize 仍必须保留 xsec_token —— 解析请求不能受影响（与 dedupKey 分工） */
    @Test
    fun `dedupKey 与 normalize 分工 前者剥后者留`() {
        val url = "https://www.xiaohongshu.com/explore/65f0a1b2c3d4e5f60718293a?xsec_token=KEEP_ME"
        assertTrue(UrlUtil.normalize(url).contains("xsec_token=KEEP_ME"))
        assertTrue(!UrlUtil.dedupKey(url).contains("xsec_token"))
    }
}

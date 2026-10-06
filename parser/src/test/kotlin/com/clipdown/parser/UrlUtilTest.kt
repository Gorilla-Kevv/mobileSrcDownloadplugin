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
}

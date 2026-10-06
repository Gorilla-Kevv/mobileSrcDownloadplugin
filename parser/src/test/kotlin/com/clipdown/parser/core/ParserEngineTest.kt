package com.clipdown.parser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 短链展开的中转页解析。
 *
 * xhslink.com / v.douyin.com 这类短链常常**不返回 3xx**，而是 200 + 一张 meta refresh 或
 * JS 跳转的中转页；OkHttp 不会跟随，必须自己把目标地址抠出来（见 ParserEngine.expand）。
 */
class ParserEngineTest {

    @Test
    fun `中转页 meta refresh 提取跳转目标`() {
        val html = """
            <html><head><meta http-equiv="refresh" content="0;url=https://www.xiaohongshu.com/explore/abc?xsec_token=T"></head></html>
        """.trimIndent()
        assertEquals(
            "https://www.xiaohongshu.com/explore/abc?xsec_token=T",
            ParserEngine.redirectFromHtml(html)
        )
    }

    @Test
    fun `中转页 location href 提取跳转目标`() {
        val html = """<html><body><script>window.location.href="https://www.xiaohongshu.com/explore/abc";</script></body></html>"""
        assertEquals(
            "https://www.xiaohongshu.com/explore/abc",
            ParserEngine.redirectFromHtml(html)
        )
    }

    @Test
    fun `中转页 location replace 提取跳转目标`() {
        val html = """<script>location.replace('https://www.xiaohongshu.com/explore/xyz')</script>"""
        assertEquals(
            "https://www.xiaohongshu.com/explore/xyz",
            ParserEngine.redirectFromHtml(html)
        )
    }

    /**
     * 真实取证（2026-10，用户分享的 xhslink.cn 短链）：短链返回 **200 + 一张纯 `<a href>`**，
     * 既没有 3xx 也没有 meta/JS 跳转。此前 `expand()` 因此原样返回短链，
     * 后续请求拿到的是首页 → 整条小红书链路必然失败。
     */
    @Test
    fun `短链中转页 纯 a href 提取跳转目标`() {
        val body = """<html><body><a href="https://www.xiaohongshu.com/discovery/item/6ac3c5c4000000001500ef52?xsec_token=CBK-abc%3D&amp;type=normal&amp;share_channel=copy_link">点击查看</a></body></html>"""
        assertEquals(
            "https://www.xiaohongshu.com/discovery/item/6ac3c5c4000000001500ef52?xsec_token=CBK-abc%3D&type=normal&share_channel=copy_link",
            ParserEngine.redirectFromHtml(body, "https://xhslink.cn/o/7mDR2JlydL0")
        )
    }

    /** 中转页里的 CDN / 脚本地址不能被当成跳转目标（只认已知平台域名） */
    @Test
    fun `中转页 不把 CDN 链接当跳转目标`() {
        val body = """<html><head><script src="https://cdn.example.com/a.js"></script></head><body></body></html>"""
        assertNull(ParserEngine.redirectFromHtml(body, "https://xhslink.cn/o/x"))
    }

    /** 指向短链自身域名的链接也不算跳转目标（防自环） */
    @Test
    fun `中转页 忽略指向自身域名的链接`() {
        val body = """<html><body><a href="https://xhslink.cn/o/other">x</a></body></html>"""
        assertNull(ParserEngine.redirectFromHtml(body, "https://xhslink.cn/o/7mDR2JlydL0"))
    }

    /**
     * 未登录时平台把作品页/主页跳到 `/login?redirectPath=<真实地址>`。
     * 短链展开若直接采用最终 URL，就会把**登录页**当成目标——
     * 实测导致主页链接被判成"笔记页"，整条主页链路进不去。
     */
    @Test
    fun `登录跳转还原出真实地址`() {
        val login = "https://www.xiaohongshu.com/login?redirectPath=" +
            "http%3A%2F%2Fwww.xiaohongshu.com%2Fuser%2Fprofile%2F561d336a33f60c555d600dec" +
            "%3Fxsec_token%3DABC%253D%26xsec_source%3Dapp_share"
        val target = ParserEngine.loginRedirectTarget(login)
        assertTrue("应还原出真实地址: $target", target!!.startsWith("http://www.xiaohongshu.com/user/profile/"))
        assertTrue("应保留 xsec_token: $target", target.contains("xsec_token"))
    }

    @Test
    fun `非登录跳转不做处理`() {
        assertNull(ParserEngine.loginRedirectTarget("https://www.xiaohongshu.com/explore/abc"))
        assertNull(ParserEngine.loginRedirectTarget("https://www.xiaohongshu.com/login"))
    }

    @Test
    fun `普通笔记页不误判为跳转`() {
        val html = """<html><body><script>window.__INITIAL_STATE__={"note":{}}</script></body></html>"""
        assertNull(ParserEngine.redirectFromHtml(html))
        assertNull(ParserEngine.redirectFromHtml(null))
        assertNull(ParserEngine.redirectFromHtml(""))
    }
}

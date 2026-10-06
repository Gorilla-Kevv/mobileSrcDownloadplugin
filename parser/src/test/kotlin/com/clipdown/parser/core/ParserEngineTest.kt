package com.clipdown.parser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `普通笔记页不误判为跳转`() {
        val html = """<html><body><script>window.__INITIAL_STATE__={"note":{}}</script></body></html>"""
        assertNull(ParserEngine.redirectFromHtml(html))
        assertNull(ParserEngine.redirectFromHtml(null))
        assertNull(ParserEngine.redirectFromHtml(""))
    }
}

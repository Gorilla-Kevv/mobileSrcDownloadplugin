package com.clipdown.parser

import com.clipdown.parser.parsers.HtmlUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON 字段提取的边界。
 *
 * 这里锁的是一个真实踩过的坑：值里含**转义引号**时被截断
 * （小红书标题「…坦承\"毕生最大遗憾\"…」只取到前半段，页面显示的标题就是残的）。
 */
class HtmlUtilTest {

    @Test
    fun `值里的转义引号不截断`() {
        val json = """{"displayTitle":"莱昂纳多首度坦承\"毕生最大遗憾\"：30年前拒绝邀约","type":"normal"}"""
        assertEquals(
            "莱昂纳多首度坦承\"毕生最大遗憾\"：30年前拒绝邀约",
            HtmlUtil.jsonField(json, "displayTitle").first()
        )
    }

    /** 无引号的数字值（SSR 里 `"posted":4127` 这种）也要能取到 */
    @Test
    fun `无引号数字值可提取`() {
        assertEquals(listOf("4127"), HtmlUtil.jsonField("""{"posted":4127,"liked":419312}""", "posted"))
        assertEquals(listOf("419312"), HtmlUtil.jsonField("""{"posted":4127,"liked":419312}""", "liked"))
        assertEquals(listOf("true"), HtmlUtil.jsonField("""{"show":true,"name":"x"}""", "show"))
    }

    @Test
    fun `普通值与多字段并存`() {
        val json = """{"a":"1","title":"标题","b":"2","title":"第二"}"""
        assertEquals(listOf("标题", "第二"), HtmlUtil.jsonField(json, "title"))
        assertEquals("1", HtmlUtil.jsonField(json, "a").first())
    }

    @Test
    fun `转义斜杠与 unicode 正常还原`() {
        val json = """{"url":"http:\u002F\u002Fsns-webpic-qc.xhscdn.com\u002Fa\u002Fb.jpg"}"""
        assertEquals("http://sns-webpic-qc.xhscdn.com/a/b.jpg", HtmlUtil.jsonField(json, "url").first())
    }

    /** 键名相似不能被误命中：urlDefault 不应被 "url" 取到 */
    @Test
    fun `相似键名不互相污染`() {
        val json = """{"url":"A","urlDefault":"B","urlPre":"C"}"""
        assertEquals(listOf("A"), HtmlUtil.jsonField(json, "url"))
        assertEquals(listOf("B"), HtmlUtil.jsonField(json, "urlDefault"))
        assertEquals(listOf("C"), HtmlUtil.jsonField(json, "urlPre"))
    }

    /** 双反斜杠形态（IG 的 JSON-in-JS）：`\\"` 在源码里是字面反斜杠+引号，仍不应截断 */
    @Test
    fun `双反斜杠转义形态可解析`() {
        val json = """{"caption":"line1\\nline2 with \"quote\""}"""
        val v = HtmlUtil.jsonField(json, "caption").first()
        assertTrue("应保留引号: $v", v.contains("quote"))
        assertTrue("应还原换行: $v", v.contains("\n"))
    }
}

package com.clipdown.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定「手机扫电脑二维码」的载荷契约（阶段 83）。
 *
 * 历史教训：最初把方向做反了——让电脑用摄像头去扫手机屏幕。
 * 这里的用例把新方向钉死：二维码里装的是**手机自己的地址 + PIN**，
 * 手机扫到后直接确认配对，不再回连电脑（扩展是 MV3，无法监听端口）。
 */
class PairPayloadTest {

    @Test
    fun `解析标准配对载荷`() {
        val p = PairPayload.parse("clipdown://pair?url=http%3A%2F%2F192.168.1.108%3A8765&pin=839201&token=ab12cd34")
        assertEquals("http://192.168.1.108:8765", p?.phoneUrl)
        assertEquals("839201", p?.pin)
        assertEquals("ab12cd34", p?.token)
    }

    @Test
    fun `未编码的地址也能解析`() {
        val p = PairPayload.parse("clipdown://pair?url=http://192.168.1.5:8765&pin=123456")
        assertEquals("http://192.168.1.5:8765", p?.phoneUrl)
        assertEquals("123456", p?.pin)
    }

    /** 旧字段名 pc 也要兼容，避免旧二维码直接失效 */
    @Test
    fun `兼容旧 pc 字段`() {
        val p = PairPayload.parse("clipdown://pair?pc=http://10.0.0.7:8765&token=x")
        assertEquals("http://10.0.0.7:8765", p?.phoneUrl)
        assertEquals("", p?.pin)
    }

    @Test
    fun `末尾斜杠被去掉`() {
        assertEquals("http://192.168.1.9:8765", PairPayload.parse("clipdown://pair?url=http://192.168.1.9:8765/")?.phoneUrl)
    }

    @Test
    fun `非 clipdown 协议一律拒绝`() {
        assertNull(PairPayload.parse("https://example.com/pair?url=http://1.2.3.4:8765"))
        assertNull(PairPayload.parse("http://1.2.3.4:8765"))
        assertNull(PairPayload.parse(""))
        assertNull(PairPayload.parse(null))
    }

    @Test
    fun `缺少 url 拒绝`() {
        assertNull(PairPayload.parse("clipdown://pair?pin=123456"))
    }

    @Test
    fun `非法地址拒绝`() {
        assertNull(PairPayload.parse("clipdown://pair?url=ftp://1.2.3.4:8765&pin=123456"))
        assertNull(PairPayload.parse("clipdown://pair?url=not-a-url&pin=123456"))
    }

    @Test
    fun `PIN 只保留数字且截断六位`() {
        assertEquals("123456", PairPayload.parse("clipdown://pair?url=http://a.io:8765&pin=12-34-56-78")?.pin)
    }

    @Test
    fun `地址形态校验`() {
        assertTrue(PairPayload.isValidHttpUrl("http://192.168.1.1:8765"))
        assertTrue(PairPayload.isValidHttpUrl("https://a.example.com"))
        assertFalse(PairPayload.isValidHttpUrl("192.168.1.1:8765"))
        assertFalse(PairPayload.isValidHttpUrl("ftp://192.168.1.1"))
    }
}

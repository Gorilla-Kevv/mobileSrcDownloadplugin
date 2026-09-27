package com.clipdown.parser

import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.core.PlatformRegistry
import com.clipdown.parser.model.Platform
import com.clipdown.parser.parsers.BilibiliParser
import com.clipdown.parser.parsers.InstagramParser
import com.clipdown.parser.parsers.XParser
import com.clipdown.parser.parsers.XiaohongshuParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlatformRegistryTest {

    @Before
    fun setUp() {
        PlatformRegistry.registerAll(ParserEngine.defaultParsers())
    }

    private fun resolve(url: String, platform: Platform) =
        PlatformRegistry.resolveForUrl(url, platform)

    @Test
    fun `X 链接路由到 XParser`() {
        val p = resolve("https://x.com/a/status/2094077925989515691", Platform.X)
        assertTrue(p is XParser)
    }

    @Test
    fun `IG reel 路由到 InstagramParser`() {
        val p = resolve("https://www.instagram.com/reel/DczssLAbCdE/", Platform.INSTAGRAM)
        assertTrue(p is InstagramParser)
    }

    @Test
    fun `B 站链接路由到 BilibiliParser`() {
        val p = resolve("https://www.bilibili.com/video/BV1GJ411x7h7", Platform.BILIBILI)
        assertTrue(p is BilibiliParser)
    }

    @Test
    fun `小红书路由到 XiaohongshuParser`() {
        val p = resolve(
            "https://www.xiaohongshu.com/explore/6ab7ffb0?xsec_token=T",
            Platform.XIAOHONGSHU
        )
        assertTrue(p is XiaohongshuParser)
    }

    @Test
    fun `注册去重 同 id 覆盖`() {
        val before = PlatformRegistry.all().count { it.id == "x-syndication-v1" }
        PlatformRegistry.register(XParser())
        val after = PlatformRegistry.all().count { it.id == "x-syndication-v1" }
        assertEquals(1, after)
        assertEquals(1, before)
    }

    @Test
    fun `全平台解析器均已注册`() {
        val ids = PlatformRegistry.all().map { it.id }
        assertTrue(ids.containsAll(listOf("x-syndication-v1", "ig-local-v1", "xhs-local-v1", "bili-wbi-v1")))
        // 通用解析器注册在列
        assertTrue(PlatformRegistry.all().any { it.platform == Platform.GENERIC })
    }
}

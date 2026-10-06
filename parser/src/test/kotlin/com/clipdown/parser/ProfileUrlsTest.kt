package com.clipdown.parser

import com.clipdown.parser.core.LinkKind
import com.clipdown.parser.core.ProfileUrls
import com.clipdown.parser.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 主页链接识别。
 *
 * 这是本功能最容易误判的一环：Instagram 的 `/p/xxx`（作品）与 `/<username>`（主页）
 * 只差一段路径，X 的 `/status/xxx` 同理。测试把各平台的"作品页必须不被判成主页"
 * 作为与"主页必须被识别"同等重要的断言。
 */
class ProfileUrlsTest {

    // ---- 主页应被识别 ----

    @Test
    fun `识别各平台主页`() {
        assertEquals(
            Platform.XIAOHONGSHU,
            ProfileUrls.match("https://www.xiaohongshu.com/user/profile/67c01c94000000000d00ada2")?.platform
        )
        assertEquals(
            "67c01c94000000000d00ada2",
            ProfileUrls.match("https://www.xiaohongshu.com/user/profile/67c01c94000000000d00ada2")?.handle
        )
        assertEquals(Platform.INSTAGRAM, ProfileUrls.match("https://www.instagram.com/astro_anil/")?.platform)
        assertEquals("astro_anil", ProfileUrls.match("https://www.instagram.com/astro_anil/")?.handle)
        assertEquals(Platform.X, ProfileUrls.match("https://x.com/astro_anil")?.platform)
        assertEquals("astro_anil", ProfileUrls.match("https://x.com/astro_anil")?.handle)
        assertEquals(Platform.WEIBO, ProfileUrls.match("https://weibo.com/u/1642591402")?.platform)
        assertEquals("1642591402", ProfileUrls.match("https://m.weibo.cn/u/1642591402")?.handle)
        assertEquals(Platform.BILIBILI, ProfileUrls.match("https://space.bilibili.com/2")?.platform)
        assertEquals("2", ProfileUrls.match("https://space.bilibili.com/2")?.handle)
        assertEquals("MS4wLjABAAAA", ProfileUrls.match("https://www.douyin.com/user/MS4wLjABAAAA")?.handle)
        assertEquals("astro_anil", ProfileUrls.match("https://www.tiktok.com/@astro_anil")?.handle)
        assertEquals("astro_anil", ProfileUrls.match("https://www.youtube.com/@astro_anil")?.handle)
        assertEquals("UCabc123", ProfileUrls.match("https://www.youtube.com/channel/UCabc123")?.handle)
    }

    // ---- 作品页绝不能被判成主页 ----

    @Test
    fun `作品页不误判为主页`() {
        assertNull("IG 帖子页", ProfileUrls.match("https://www.instagram.com/p/ABC123/"))
        assertNull("IG reel", ProfileUrls.match("https://www.instagram.com/reel/ABC123/"))
        assertNull("IG stories", ProfileUrls.match("https://www.instagram.com/stories/abc/123/"))
        assertNull("X 推文", ProfileUrls.match("https://x.com/astro_anil/status/2094077925989515691"))
        assertNull("小红书笔记", ProfileUrls.match("https://www.xiaohongshu.com/explore/6ab7ffb0000000000b006b27"))
        assertNull("小红书 discovery 笔记", ProfileUrls.match("https://www.xiaohongshu.com/discovery/item/6ac05cc4000000001b02d811"))
        assertNull("B 站视频", ProfileUrls.match("https://www.bilibili.com/video/BV1GJ411x7h7"))
        assertNull("微博正文", ProfileUrls.match("https://weibo.com/detail/1234567890"))
        assertNull("抖音视频", ProfileUrls.match("https://www.douyin.com/video/7123456789"))
        assertNull("YouTube 视频", ProfileUrls.match("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull("YouTube shorts", ProfileUrls.match("https://www.youtube.com/shorts/abc123"))
        assertNull("平台首页", ProfileUrls.match("https://x.com/home"))
        assertNull("IG 探索页", ProfileUrls.match("https://www.instagram.com/explore/"))
    }

    @Test
    fun `非受支持域名不判为主页`() {
        assertNull(ProfileUrls.match("https://example.com/someone"))
        assertNull(ProfileUrls.match("https://www.xiaohongshu.com/"))
    }

    @Test
    fun `链接形态判定`() {
        assertEquals(LinkKind.PROFILE, ProfileUrls.kindOf("https://x.com/astro_anil"))
        assertEquals(LinkKind.POST, ProfileUrls.kindOf("https://x.com/astro_anil/status/123"))
        assertEquals(LinkKind.UNKNOWN, ProfileUrls.kindOf("https://example.com/a"))
    }
}

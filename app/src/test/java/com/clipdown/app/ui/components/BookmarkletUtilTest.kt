package com.clipdown.app.ui.components

import com.clipdown.parser.model.Platform
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkletUtilTest {

    @Test
    fun `Instagram 书签脚本生成包含 sessionid 校验与 DeepLink 回传`() {
        val script = BookmarkletUtil.generate(Platform.INSTAGRAM)
        assertTrue(script.startsWith("javascript:(function(){"))
        assertTrue(script.contains("sessionid"))
        assertTrue(script.contains("clipdown://cookie?platform=instagram&data="))
        assertTrue(script.contains("navigator.clipboard.writeText"))
        assertTrue(script.endsWith("})();"))
    }

    @Test
    fun `小红书书签脚本生成包含 web_session 校验`() {
        val script = BookmarkletUtil.generate(Platform.XIAOHONGSHU)
        assertTrue(script.contains("web_session"))
        assertTrue(script.contains("clipdown://cookie?platform=xiaohongshu&data="))
    }

    @Test
    fun `Facebook 书签脚本生成包含 c_user 校验`() {
        val script = BookmarkletUtil.generate(Platform.FACEBOOK)
        assertTrue(script.contains("c_user"))
        assertTrue(script.contains("clipdown://cookie?platform=facebook&data="))
    }
}

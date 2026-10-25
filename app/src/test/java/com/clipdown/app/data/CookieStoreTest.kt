package com.clipdown.app.data

import com.clipdown.parser.model.Platform
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieStoreTest {

    @Test
    fun `Instagram Cookie 严格校验 sessionid`() {
        assertFalse(CookieStore.isValidSessionCookie(Platform.INSTAGRAM, "mid=Z7r; csrftoken=abc"))
        assertFalse(CookieStore.isValidSessionCookie(Platform.INSTAGRAM, ""))
        assertTrue(CookieStore.isValidSessionCookie(Platform.INSTAGRAM, "mid=Z7r; csrftoken=abc; sessionid=123%3Axyz"))
    }

    @Test
    fun `小红书 Cookie 校验 web_session 或 a1`() {
        assertFalse(CookieStore.isValidSessionCookie(Platform.XIAOHONGSHU, "guest=xyz"))
        assertTrue(CookieStore.isValidSessionCookie(Platform.XIAOHONGSHU, "web_session=010203"))
        assertTrue(CookieStore.isValidSessionCookie(Platform.XIAOHONGSHU, "a1=18a123; gid=456"))
    }

    @Test
    fun `Facebook Cookie 校验 c_user 或 xs`() {
        assertFalse(CookieStore.isValidSessionCookie(Platform.FACEBOOK, "fr=abc123"))
        assertTrue(CookieStore.isValidSessionCookie(Platform.FACEBOOK, "c_user=10001; xs=abcd"))
        assertTrue(CookieStore.isValidSessionCookie(Platform.FACEBOOK, "xs=abcd"))
    }
}

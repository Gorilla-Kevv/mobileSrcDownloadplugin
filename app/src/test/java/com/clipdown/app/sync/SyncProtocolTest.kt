package com.clipdown.app.sync

import com.clipdown.app.data.CookieStore
import com.clipdown.parser.model.Platform
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun testSyncStatusSerialization() {
        val status = SyncStatusResponse(
            deviceName = "Xiaomi 14",
            appVersion = "1.1.25",
            port = 8765,
            paired = true,
            taskCount = 12,
            loggedInPlatforms = listOf("instagram", "xiaohongshu")
        )

        val str = json.encodeToString(status)
        assertTrue(str.contains("Xiaomi 14"))
        assertTrue(str.contains("1.1.25"))
        assertTrue(str.contains("8765"))

        val decoded = json.decodeFromString<SyncStatusResponse>(str)
        assertEquals("Xiaomi 14", decoded.deviceName)
        assertEquals(8765, decoded.port)
        assertTrue(decoded.paired)
        assertEquals(2, decoded.loggedInPlatforms.size)
    }

    @Test
    fun testSyncCookieBatchSerialization() {
        val req = SyncCookieBatchRequest(
            cookies = mapOf(
                "instagram" to "sessionid=test_session_123; csrftoken=abc",
                "xiaohongshu" to "web_session=test_xhs_session; a1=xyz"
            )
        )

        val str = json.encodeToString(req)
        assertTrue(str.contains("sessionid=test_session_123"))

        val decoded = json.decodeFromString<SyncCookieBatchRequest>(str)
        assertEquals(2, decoded.cookies.size)
        assertTrue(CookieStore.isValidSessionCookie(Platform.INSTAGRAM, decoded.cookies["instagram"]!!))
        assertTrue(CookieStore.isValidSessionCookie(Platform.XIAOHONGSHU, decoded.cookies["xiaohongshu"]!!))
        assertFalse(CookieStore.isValidSessionCookie(Platform.INSTAGRAM, "csrftoken=fake_only"))
    }

    @Test
    fun testSyncTasksSerialization() {
        val items = listOf(
            SyncTaskItem(
                id = "task-001",
                title = "Instagram Reel 视频",
                url = "https://instagram.com/reel/C8xyz",
                fileName = "reel.mp4",
                mimeType = "video/mp4",
                platformId = "instagram",
                coverUrl = "https://example.com/cover.jpg",
                sourceUrl = "https://instagram.com/reel/C8xyz",
                totalBytes = 15_000_000L,
                downloadedBytes = 15_000_000L,
                status = "COMPLETED"
            ),
            SyncTaskItem(
                id = "task-002",
                title = "小红书图集",
                url = "https://xiaohongshu.com/discovery/item/abc",
                fileName = "xhs.jpg",
                mimeType = "image/jpeg",
                platformId = "xiaohongshu",
                status = "COMPLETED"
            )
        )

        val resp = SyncTasksResponse(total = items.size, tasks = items)
        val str = json.encodeToString(resp)
        val decoded = json.decodeFromString<SyncTasksResponse>(str)

        assertEquals(2, decoded.total)
        assertEquals("task-001", decoded.tasks[0].id)
        assertEquals("COMPLETED", decoded.tasks[0].status)
        assertEquals("Instagram Reel 视频", decoded.tasks[0].title)
    }

    @Test
    fun testSyncEnqueueRequestSerialization() {
        val req = SyncEnqueueRequest(url = "https://www.instagram.com/p/ABC123xyz/")
        val str = json.encodeToString(req)
        val decoded = json.decodeFromString<SyncEnqueueRequest>(str)
        assertEquals("https://www.instagram.com/p/ABC123xyz/", decoded.url)
    }
}

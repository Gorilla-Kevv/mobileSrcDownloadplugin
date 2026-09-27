package com.clipdown.parser

import com.clipdown.parser.stream.M3u8Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M3u8ParserTest {

    /** 真实 odycdn 主播放列表（2026-09-27 抓取） */
    private val master = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-STREAM-INF:BANDWIDTH=4026000,RESOLUTION=1920x1080,CODECS="avc1.4d4028,mp4a.40.2",CLOSED-CAPTIONS=NONE
        v0.m3u8

        #EXT-X-STREAM-INF:BANDWIDTH=2890800,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",CLOSED-CAPTIONS=NONE
        v1.m3u8

        #EXT-X-STREAM-INF:BANDWIDTH=655600,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",CLOSED-CAPTIONS=NONE
        v2.m3u8

        #EXT-X-STREAM-INF:BANDWIDTH=215600,RESOLUTION=256x144,CODECS="avc1.4d400b,mp4a.40.2",CLOSED-CAPTIONS=NONE
        v3.m3u8
    """.trimIndent()

    private val media = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-TARGETDURATION:10
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-PLAYLIST-TYPE:VOD
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXTINF:10.000000,
        seg_0.ts
        #EXTINF:8.5,
        seg_1.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    private val aesMedia = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXT-X-KEY:METHOD=AES-128,URI="https://cdn.example.com/key.bin",IV=0x9c7db8778570d05c3177c349fd9236aa
        #EXTINF:10.0,
        seg_0.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    private val baseUrl = "https://player.odycdn.com/v6/streams/abc/7bb5/master.m3u8"

    @Test
    fun `主列表解析 相对地址补全`() {
        val p = M3u8Parser.parse(master, baseUrl)
        assertTrue(p.isMaster)
        assertEquals(4, p.variants.size)
        assertEquals(
            "https://player.odycdn.com/v6/streams/abc/7bb5/v0.m3u8",
            p.variants[0].url
        )
        assertEquals(1080, p.variants[0].height)
        assertEquals("1080p", p.variants[0].label())
        assertEquals(4026000, p.variants[0].bandwidth)
    }

    @Test
    fun `媒体列表解析 分片与时长`() {
        val p = M3u8Parser.parse(media, baseUrl)
        assertFalse(p.isMaster)
        assertTrue(p.endList)
        assertEquals(10, p.targetDuration)
        assertEquals(2, p.segments.size)
        assertEquals(10.0, p.segments[0].durationSec, 0.001)
        assertEquals(18.5, p.totalDurationSec, 0.001)
        assertEquals(
            "https://player.odycdn.com/v6/streams/abc/7bb5/seg_1.ts",
            p.segments[1].uri
        )
    }

    @Test
    fun `AES-128 密钥与 IV 解析`() {
        val p = M3u8Parser.parse(aesMedia, baseUrl)
        val key = p.segments[0].key!!
        assertEquals("AES-128", key.method)
        assertEquals("https://cdn.example.com/key.bin", key.uri)
        assertEquals("9c7db8778570d05c3177c349fd9236aa", key.ivHex)
    }

    @Test
    fun `空行注释容错`() {
        val p = M3u8Parser.parse("#EXTM3U\n\n#注释行\n\n", baseUrl)
        assertTrue(p.segments.isEmpty() && p.variants.isEmpty())
    }
}

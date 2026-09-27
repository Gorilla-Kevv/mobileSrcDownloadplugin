package com.clipdown.parser.parsers

import com.clipdown.parser.model.MediaItem
import com.clipdown.parser.model.MediaKind
import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.ParseResult
import com.clipdown.parser.model.ParseSource
import com.clipdown.parser.model.Platform
import com.clipdown.parser.spi.ParseContext
import com.clipdown.parser.spi.PlatformParser
import com.clipdown.parser.spi.ParserDsl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * YouTube 解析器。
 *
 * YouTube 的地址签名（nsig / sig 参数）更新极其频繁，不适合在客户端硬编码实现，
 * 因此本解析器走"公开中继实例"路线：依次尝试一组 Piped / Invidious 实例，
 * 取第一个可用实例的 streams / adaptiveFormats。
 *
 * 若用户已在设置里配置远端解析服务（cobalt 自建），解析内核会优先走那条链路。
 */
class YoutubeParser : PlatformParser {

    override val platform: Platform = Platform.YOUTUBE
    override val id: String = "youtube-relay-v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val idRegex = Regex("""(?:v=|/shorts/|/live/|youtu\.be/|/embed/)([A-Za-z0-9_-]{6,15})""")

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.reallyaweso.me"
    )

    override fun canHandle(url: String): Boolean = idRegex.containsMatchIn(url)

    override fun parse(url: String, ctx: ParseContext): ParseResult {
        val videoId = idRegex.find(url)?.groupValues?.getOrNull(1)
            ?: throw ParseException("无法从链接中提取视频 ID", platform, retryable = false)

        for (base in pipedInstances) {
            val r = runCatching { tryPiped(base, videoId, url, ctx) }.getOrNull()
            if (r != null && !r.isEmpty) return r
        }
        throw ParseException(
            "YouTube 需要借助远端解析服务，请在设置中开启并配置自建服务（cobalt / yt-dlp 服务端）",
            platform
        )
    }

    private fun tryPiped(base: String, videoId: String, url: String, ctx: ParseContext): ParseResult? {
        val headers = mapOf("User-Agent" to ctx.config.userAgents.desktop, "Accept" to "application/json")
        val body = ctx.http.get("$base/streams/$videoId", headers).body ?: return null
        val root = json.parseToJsonElement(body).jsonObject

        val title = root["title"]?.jsonPrimitive?.contentOrNull
        val author = root["uploader"]?.jsonPrimitive?.contentOrNull
        val cover = root["thumbnailUrl"]?.jsonPrimitive?.contentOrNull

        val media = mutableListOf<MediaItem>()

        // 合并流（videoStreams）：直接可播的 mp4；HLS 流标记为播放列表交给 M3u8 下载器
        root["videoStreams"]?.jsonArray?.forEachIndexed { i, el ->
            val o = el.jsonObject
            val u = o["url"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
            val format = o["format"]?.jsonPrimitive?.contentOrNull ?: ""
            val mime = o["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
            val isHls = format == "HLS" || format == "MIME_TYPE_VIDEO_HLS" ||
                mime.contains("mpegurl", ignoreCase = true) || u.substringBefore('?').endsWith(".m3u8")
            if (isHls) {
                media += MediaItem(
                    id = "yt-hls-$i",
                    url = u,
                    kind = MediaKind.VIDEO,
                    quality = o["quality"]?.jsonPrimitive?.contentOrNull ?: "HLS",
                    rank = 90 - i,
                    container = "m3u8",
                    mimeType = "application/x-mpegurl",
                    isPlaylist = true,
                    fileNameHint = title
                )
                return@forEachIndexed
            }
            media += MediaItem(
                id = "yt-v-$i",
                url = u,
                kind = MediaKind.VIDEO,
                quality = o["quality"]?.jsonPrimitive?.contentOrNull ?: "默认",
                rank = 100 - i,
                bitrateKbps = o["bitrate"]?.jsonPrimitive?.intOrNull,
                container = "mp4",
                mimeType = "video/mp4",
                fileNameHint = title
            )
        }

        // 自适应流（audioStreams）：单独下载音频
        root["audioStreams"]?.jsonArray?.take(2)?.forEachIndexed { i, el ->
            val o = el.jsonObject
            val u = o["url"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
            media += MediaItem(
                id = "yt-a-$i",
                url = u,
                kind = MediaKind.AUDIO,
                quality = "音频 ${o["quality"]?.jsonPrimitive?.contentOrNull ?: ""}".trim(),
                rank = 50 - i,
                bitrateKbps = o["bitrate"]?.jsonPrimitive?.intOrNull,
                container = "m4a",
                mimeType = "audio/mp4"
            )
        }

        if (media.isEmpty()) return null
        return ParserDsl.result(
            platform = platform,
            resolverId = "$id-piped",
            sourceUrl = url,
            media = media,
            title = title,
            author = author,
            cover = cover,
            source = ParseSource.REMOTE,
            warning = "通过公开中继实例解析，稳定性取决于实例可用性"
        )
    }
}

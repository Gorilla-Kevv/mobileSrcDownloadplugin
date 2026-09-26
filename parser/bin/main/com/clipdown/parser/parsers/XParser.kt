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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * X（Twitter）解析器。
 *
 * 主通道是 X 公开的 syndication 接口（无需登录）：
 * `https://cdn.syndication.twimg.com/tweet-result?id={id}&token={token}`
 * 其中 token 由推文 ID 推导：((id / 1e15) * π).toString(36) 去掉 '.' 与 '0'。
 *
 * 该接口返回标准的 `mediaDetails[].video_info.variants[]`（多清晰度 MP4）与 `photos[]`。
 */
class XParser : PlatformParser {

    override val platform: Platform = Platform.X
    override val id: String = "x-syndication-v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val idRegex = Regex("""/status(?:es)?/(\d{5,25})""")

    override fun canHandle(url: String): Boolean = idRegex.containsMatchIn(url)

    override fun parse(url: String, ctx: ParseContext): ParseResult {
        val tweetId = idRegex.find(url)?.groupValues?.getOrNull(1)
            ?: throw ParseException("无法从链接中提取推文 ID", platform, retryable = false)

        val token = guestToken(tweetId)
        val api = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=zh-cn&token=$token"
        val headers = ctx.headersFor(platform) + mapOf(
            "Referer" to "https://platform.twitter.com/",
            "Origin" to "https://platform.twitter.com",
            "Accept" to "application/json"
        )

        val resp = runCatching { ctx.http.get(api, headers) }
            .getOrElse { throw ParseException("网络请求失败：${it.message}", platform) }
        val body = resp.body
            ?: throw ParseException("公开接口返回空数据", platform)
        if (resp.code >= 400) throw ParseException("公开接口返回 ${resp.code}", platform)

        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw ParseException("接口数据结构异常", platform) }

        val media = mutableListOf<MediaItem>()
        val xHeaders = mapOf("Referer" to "https://x.com/")

        root["mediaDetails"]?.jsonArray?.forEach { el ->
            val o = el.jsonObject
            val variants = o["video_info"]?.jsonObject?.get("variants")?.jsonArray
            variants?.mapNotNull { v ->
                val vo = v.jsonObject
                val u = vo.str("url") ?: return@mapNotNull null
                val ct = vo.str("content_type") ?: ""
                if (!ct.contains("mp4")) return@mapNotNull null
                val bitrate = vo.int("bitrate") ?: 0
                u to bitrate
            }?.sortedByDescending { it.second }?.forEachIndexed { i, (u, bitrate) ->
                media += MediaItem(
                    id = "x-v-$i",
                    url = u,
                    kind = MediaKind.VIDEO,
                    quality = bitrateLabel(bitrate, i),
                    rank = 100 - i,
                    container = "mp4",
                    mimeType = "video/mp4",
                    headers = xHeaders
                )
            }
            o["photo_info"]?.jsonObject?.str("url")?.let { u ->
                media += MediaItem(
                    id = "x-p-${media.size}",
                    url = u,
                    kind = MediaKind.IMAGE,
                    quality = "原图",
                    rank = 80,
                    container = "jpg",
                    headers = xHeaders
                )
            }
        }

        root["photos"]?.jsonArray?.forEachIndexed { i, el ->
            val u = el.jsonObject.str("url") ?: el.jsonObject.str("expanded_url") ?: return@forEachIndexed
            val base = u.substringBefore("?format=")
            media += MediaItem(
                id = "x-ph-$i",
                url = "$base?format=jpg&name=orig",
                kind = MediaKind.IMAGE,
                quality = "原图 ${i + 1}",
                rank = 90 - i,
                container = "jpg",
                headers = xHeaders
            )
        }

        if (media.isEmpty()) throw ParseException("该推文没有可下载的媒体（可能是纯文本或已删除）", platform)

        val user = root["user"]?.jsonObject
        return ParserDsl.result(
            platform = platform,
            resolverId = id,
            sourceUrl = url,
            media = media,
            title = root.str("text")?.take(60),
            author = user?.str("name") ?: user?.str("screen_name"),
            cover = media.firstOrNull { it.kind == MediaKind.IMAGE }?.url,
            source = ParseSource.OFFICIAL
        )
    }

    /**
     * syndication 接口的 guest token 推导：((id / 1e15) * π) 转 36 进制后去掉 '.' 与 '0'。
     * JVM 没有 Double.toString(radix)，这里自行实现整数 + 小数部分的进制转换。
     */
    fun guestToken(tweetId: String): String {
        val id = tweetId.toDoubleOrNull() ?: return ""
        val v = (id / 1e15) * Math.PI
        return toBase36(v).replace(Regex("""0+|\."""), "")
    }

    private fun toBase36(value: Double, fracDigits: Int = 12): String {
        val intPart = value.toLong()
        var frac = value - intPart
        val sb = StringBuilder(intPart.toString(36))
        if (frac > 0.0) {
            sb.append('.')
            repeat(fracDigits) {
                frac *= 36.0
                val d = frac.toLong()
                sb.append(d.toString(36))
                frac -= d.toDouble()
                if (frac <= 0.0) return@repeat
            }
        }
        return sb.toString()
    }

    private fun bitrateLabel(bitrate: Int, index: Int): String = when {
        bitrate > 5_000_000 -> "高码率"
        bitrate > 2_000_000 -> "中码率"
        bitrate > 0 -> "低码率"
        else -> "备选 ${index + 1}"
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
}

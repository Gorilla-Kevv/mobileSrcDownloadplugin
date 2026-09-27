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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * B 站解析器（含 WBI 签名实现）。
 *
 * B 站播放地址接口自 2023 年起要求 WBI 签名，未签名请求直接返回 -403。
 * 这里完整实现了签名流程，因此不依赖任何第三方代理：
 * 1. `/x/web-interface/nav` 取 img_key / sub_key
 * 2. 按固定乱序表重组得到 32 位 mixin key
 * 3. 参数排序 + 追加 wts 时间戳 + md5(query + mixin_key) 得到 w_rid
 *
 * 播放地址优先取 `fnval=1` 的 durl 直链（单一 MP4，下载最省事）；
 * 若只有 DASH 则返回视频轨与音频轨，由下载器合并。
 */
class BilibiliParser : PlatformParser {

    override val platform: Platform = Platform.BILIBILI
    override val id: String = "bili-wbi-v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val bvidRegex = Regex("""(BV[0-9A-Za-z]{10})""")
    private val avidRegex = Regex("""/av(\d+)""")

    override fun canHandle(url: String): Boolean = bvidRegex.containsMatchIn(url) || avidRegex.containsMatchIn(url)

    override fun parse(url: String, ctx: ParseContext): ParseResult {
        val bvid = bvidRegex.find(url)?.groupValues?.getOrNull(1)
        val avid = avidRegex.find(url)?.groupValues?.getOrNull(1)
        if (bvid == null && avid == null) throw ParseException("无法从链接中提取稿件 ID", platform, retryable = false)

        // B 站风控：playurl 对无 buvid3 cookie 的请求返回 HTML 拦截页（HTTP 404/412）。
        // 先访问视频页预热 cookie，把 Set-Cookie 合并进后续 API 请求。
        val headers = warmUpCookie(
            url,
            ctx.headersFor(platform) + mapOf(
                "Referer" to "https://www.bilibili.com/",
                "Origin" to "https://www.bilibili.com",
                "Accept" to "application/json, text/plain, */*"
            ),
            ctx
        )

        val viewQuery = if (bvid != null) "bvid=$bvid" else "aid=$avid"
        val viewRaw = runCatching { ctx.http.get("$API/view?$viewQuery", headers).body }.getOrNull()
            ?: throw ParseException("无法获取稿件信息", platform)
        val view = runCatching { json.parseToJsonElement(viewRaw).jsonObject }.getOrElse {
            throw ParseException("稿件接口返回异常格式", platform)
        }
        if (view["code"]?.jsonPrimitive?.intOrNull != 0) {
            throw ParseException("稿件接口错误：${view["message"]?.jsonPrimitive?.contentOrNull}", platform)
        }
        val data = view["data"]?.jsonObject ?: throw ParseException("稿件数据为空", platform)
        val cid = data["cid"]?.jsonPrimitive?.contentOrNull
            ?: throw ParseException("无法获取 cid", platform)
        val title = data["title"]?.jsonPrimitive?.contentOrNull
        val author = data["owner"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        val cover = data["pic"]?.jsonPrimitive?.contentOrNull
        val desc = data["desc"]?.jsonPrimitive?.contentOrNull?.take(120)

        val signed = wbiParams(
            linkedMapOf(
                (if (bvid != null) "bvid" to bvid else "aid" to avid!!),
                "cid" to cid,
                "qn" to "80",
                "fnval" to "1",
                "fourk" to "1",
                "otype" to "json"
            ),
            ctx,
            headers
        )
        val playResp = runCatching { ctx.http.get("$API/playurl?$signed", headers) }.getOrNull()
            ?: throw ParseException("无法获取播放地址", platform)
        val playRaw = playResp.body ?: throw ParseException("播放地址响应为空", platform)
        val play = runCatching { json.parseToJsonElement(playRaw).jsonObject }.getOrElse {
            ctx.log(
                id,
                "playurl 异常响应：code=${playResp.code} len=${playRaw.length} head=" +
                    playRaw.take(160).replace(Regex("\\s+"), " ")
            )
            throw ParseException("播放地址接口返回异常格式", platform)
        }

        if (play["code"]?.jsonPrimitive?.intOrNull != 0) {
            throw ParseException(
                "播放地址获取失败：${play["message"]?.jsonPrimitive?.contentOrNull ?: "可能需要登录"}",
                platform
            )
        }
        val playData = play["data"]?.jsonObject ?: throw ParseException("播放数据为空", platform)

        val media = mutableListOf<MediaItem>()
        val mediaHeaders = buildMap {
            put("Referer", "https://www.bilibili.com/")
            headers["Cookie"]?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }
        val durl = playData["durl"]?.jsonArray
        durl?.forEachIndexed { i, el ->
            val u = el.jsonObject.str("url") ?: return@forEachIndexed
            media += MediaItem(
                id = "bili-durl-$i",
                url = u,
                kind = MediaKind.VIDEO,
                quality = qualityLabel(playData["quality"]?.jsonPrimitive?.intOrNull),
                rank = 100 - i,
                sizeBytes = el.jsonObject["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                container = "flv",
                mimeType = "video/x-flv",
                headers = mediaHeaders,
                fileNameHint = title
            )
        }

        // DASH 兜底：视频轨 + 音频轨，交给下载器合并
        if (media.isEmpty()) {
            val dash = playData["dash"]?.jsonObject
            val videos = dash?.get("video")?.jsonArray ?: JsonArray(emptyList())
            val audios = dash?.get("audio")?.jsonArray ?: JsonArray(emptyList())
            val bestVideo = videos.mapNotNull { it.jsonObject }
                .sortedByDescending { it.int("bandwidth") ?: 0 }
                .firstOrNull()
            val bestAudio = audios.mapNotNull { it.jsonObject }
                .sortedByDescending { it.int("bandwidth") ?: 0 }
                .firstOrNull()
            val vu = bestVideo?.str("baseUrl") ?: bestVideo?.str("base_url")
            if (vu != null) {
                media += MediaItem(
                    id = "bili-dash-v",
                    url = vu,
                    kind = MediaKind.VIDEO,
                    quality = "${bestVideo?.int("height") ?: 0}p",
                    rank = 100,
                    width = bestVideo?.int("width"),
                    height = bestVideo?.int("height"),
                    container = "m4s",
                    mimeType = "video/mp4",
                    headers = mediaHeaders,
                    audioUrl = bestAudio?.str("baseUrl") ?: bestAudio?.str("base_url"),
                    audioHeaders = mediaHeaders,
                    needsRemux = true,
                    fileNameHint = title
                )
            }
        }

        if (media.isEmpty()) throw ParseException("未能获取可下载的媒体流", platform)

        return ParserDsl.result(
            platform = platform,
            resolverId = id,
            sourceUrl = url,
            media = media,
            title = title,
            author = author,
            cover = cover,
            source = ParseSource.OFFICIAL
        ).copy(description = desc)
    }

    /** WBI 签名：返回拼好的 query string */
    private fun wbiParams(
        params: LinkedHashMap<String, String>,
        ctx: ParseContext,
        headers: Map<String, String>
    ): String {
        val keys = runCatching { fetchWbiKeys(ctx, headers) }.getOrNull()
        if (keys.isNullOrBlank()) {
            ctx.log(id, "WBI 密钥获取失败，playurl 将以未签名请求降级")
            return params.entries.joinToString("&") { "${it.key}=${it.value}" }
        }
        val sorted = params.toSortedMap()
        val wts = System.currentTimeMillis() / 1000
        val query = sorted.entries.joinToString("&") { "${it.key}=${urlEncode(it.value)}" } + "&wts=$wts"
        val wRid = md5(query + keys)
        return "$query&w_rid=$wRid"
    }

    /**
     * cookie 预热：无 buvid3 时先访问视频页（比主站下发更多风控 cookie），
     * 收集 Set-Cookie 并合并进 headers 的 Cookie。失败不影响原 headers。
     */
    private fun warmUpCookie(pageUrl: String, headers: Map<String, String>, ctx: ParseContext): Map<String, String> {
        val existing = headers["Cookie"]
        if (!existing.isNullOrBlank() && existing.contains("buvid3")) return headers
        val warm = runCatching {
            ctx.http.get(
                pageUrl,
                mapOf(
                    "User-Agent" to ctx.ua(),
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "zh-CN,zh;q=0.9"
                )
            )
        }.getOrNull() ?: return headers
        val harvested = warm.headers.values("Set-Cookie")
            .map { it.substringBefore(';').trim() }
            .filter { it.contains('=') && !it.endsWith("=", true) }
        if (harvested.isEmpty()) return headers
        val merged = ((existing?.trimEnd(';')?.plus("; ")) ?: "") + harvested.joinToString("; ")
        ctx.log(id, "cookie 预热(${warm.code})：收集 ${harvested.size} 项（buvid3=${harvested.any { it.startsWith("buvid3") }}）")
        return headers + ("Cookie" to merged)
    }

    private fun fetchWbiKeys(ctx: ParseContext, headers: Map<String, String>): String? {
        val resp = ctx.http.get("$API/nav", headers)
        val raw = resp.body ?: return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: run {
            ctx.log(id, "nav 异常响应：code=${resp.code} head=${raw.take(120).replace(Regex("\\s+"), " ")}")
            return null
        }
        val wbi = root["data"]?.jsonObject?.get("wbi_img")?.jsonObject ?: return null
        val img = wbi.str("img_url") ?: return null
        val sub = wbi.str("sub_url") ?: return null
        val imgKey = img.substringAfterLast('/').substringBefore('.')
        val subKey = sub.substringAfterLast('/').substringBefore('.')
        if (imgKey.length < 32 || subKey.length < 16) {
            ctx.log(id, "WBI 密钥长度异常：img=$imgKey sub=$subKey")
            return null
        }
        return mixinKey(imgKey + subKey)
    }

    private fun mixinKey(raw: String): String {
        val tab = intArrayOf(
            46, 47, 18, 2, 4, 54, 45, 36, 38, 30, 7, 55, 56, 29, 27, 31, 58, 49, 39, 60,
            42, 62, 59, 1, 23, 53, 35, 34, 19, 3, 9, 5, 51, 57, 61, 26, 16, 44, 12, 13,
            64, 63, 22, 10, 21, 0, 24, 28, 17, 50, 8, 15, 20, 32, 43, 25, 33, 41, 11, 40,
            14, 37, 6, 48
        )
        return tab.map { raw[it] }.joinToString("").take(32)
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
        .replace("+", "%20").replace("*", "").replace("!", "").replace("'", "")
        .replace("(", "").replace(")", "")

    private fun qualityLabel(qn: Int?): String = when (qn) {
        120 -> "4K"
        116 -> "1080P60"
        80 -> "1080P"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> "默认画质"
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    companion object {
        private const val API = "https://api.bilibili.com/x/web-interface"
    }
}

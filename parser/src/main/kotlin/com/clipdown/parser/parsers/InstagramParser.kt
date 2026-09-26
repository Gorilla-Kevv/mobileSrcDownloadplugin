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
 * Instagram 解析器。
 *
 * 三级策略：
 * 1. 登录态直连接口（用户在设置里提供了 Cookie）：`/p/{code}/?__a=1&__d=dis`，
 *    可拿到全部清晰度与多图画质；
 * 2. 免登录 embed 页：`/p/{code}/embed/captioned/`，可拿到视频直链与单张图片；
 * 3. oEmbed 公开接口：只能拿到缩略图，作为最后兜底并给出降质提示。
 */
class InstagramParser : PlatformParser {

    override val platform: Platform = Platform.INSTAGRAM
    override val id: String = "ig-local-v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val shortcodeRegex =
        Regex("""(?:instagram\.com|instagr\.am)/(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,})""")

    override fun canHandle(url: String): Boolean = shortcodeRegex.containsMatchIn(url)

    override fun parse(url: String, ctx: ParseContext): ParseResult {
        val code = shortcodeRegex.find(url)?.groupValues?.getOrNull(1)
            ?: throw ParseException("无法从链接中提取作品 ID", platform, retryable = false)

        val cookie = ctx.cookieProvider(platform)
        val headers = ctx.headersFor(platform, desktop = false) +
            mapOf("Referer" to "https://www.instagram.com/", "Accept" to "*/*")

        // 1) 登录态直连
        if (!cookie.isNullOrBlank()) {
            val r = runCatching { parsePrivateApi(code, headers + mapOf("Cookie" to cookie), ctx) }
            if (r.isSuccess) return r.getOrThrow().copy(source = ParseSource.LOCAL)
            ctx.log(id, "登录态解析失败：${r.exceptionOrNull()?.message}")
        }

        // 2) 免登录 embed
        val embedUrl = "https://www.instagram.com/p/$code/embed/captioned/"
        val embedHtml = runCatching { ctx.http.get(embedUrl, headers).body }.getOrNull()
        if (!embedHtml.isNullOrBlank()) {
            val parsed = parseEmbed(embedHtml, url, code)
            if (!parsed.isEmpty) return parsed
        }

        // 3) oEmbed 兜底
        val oembed = runCatching {
            ctx.http.get("https://api.instagram.com/oembed/?url=$url", headers).body
        }.getOrNull()
        if (!oembed.isNullOrBlank()) {
            val thumbnail = HtmlUtil.jsonField(oembed, "thumbnail_url").firstOrNull()
            val author = HtmlUtil.jsonField(oembed, "author_name").firstOrNull()
            val title = HtmlUtil.jsonField(oembed, "title").firstOrNull()
            if (thumbnail != null) {
                return ParserDsl.result(
                    platform = platform,
                    resolverId = "$id-oembed",
                    sourceUrl = url,
                    media = listOf(
                        MediaItem(
                            id = "ig-thumb",
                            url = thumbnail,
                            kind = MediaKind.IMAGE,
                            quality = "缩略图",
                            rank = 10,
                            container = "jpg",
                            headers = mapOf("Referer" to "https://www.instagram.com/")
                        )
                    ),
                    title = title,
                    author = author,
                    cover = thumbnail,
                    source = ParseSource.OFFICIAL,
                    warning = "Instagram 已限制免登录访问，当前仅能获取缩略图。补充 Cookie 或开启远端解析可获得原画质"
                )
            }
        }

        throw ParseException("Instagram 解析失败，建议补充 Cookie 或开启远端解析服务", platform)
    }

    private fun parsePrivateApi(code: String, headers: Map<String, String>, ctx: ParseContext): ParseResult {
        val body = privateJson(ctx.http, headers, code)
            ?: throw ParseException("登录态接口未返回数据（Cookie 可能已失效）", platform)
        val root = json.parseToJsonElement(body).jsonObject
        val item = (root["items"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: root["graphql"]?.jsonObject?.get("shortcode_media")?.jsonObject
            ?: throw ParseException("接口数据结构异常", platform)

        val media = mutableListOf<MediaItem>()
        val videoVersions = item["video_versions"] as? JsonArray
        videoVersions?.forEachIndexed { i, el ->
            val o = el.jsonObject
            val u = o.str("url") ?: return@forEachIndexed
            media += MediaItem(
                id = "ig-v-$i",
                url = u,
                kind = MediaKind.VIDEO,
                quality = "${o.int("height") ?: 0}p",
                rank = 100 - i,
                width = o.int("width"),
                height = o.int("height"),
                container = "mp4",
                mimeType = "video/mp4",
                headers = mapOf("Referer" to "https://www.instagram.com/")
            )
        }

        val candidates = item["image_versions2"]?.jsonObject?.get("candidates") as? JsonArray
        candidates?.firstOrNull()?.jsonObject?.str("url")?.let { u ->
            media += MediaItem(
                id = "ig-i-0",
                url = u,
                kind = MediaKind.IMAGE,
                quality = "原图",
                rank = 80,
                container = "jpg",
                headers = mapOf("Referer" to "https://www.instagram.com/")
            )
        }

        val carousel = item["carousel_media"] as? JsonArray
        carousel?.forEachIndexed { i, el ->
            val o = el.jsonObject
            val u = (o["video_versions"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("url")
                ?: o["image_versions2"]?.jsonObject?.get("candidates")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.str("url")
                ?: return@forEachIndexed
            val isVideo = o["video_versions"] != null
            media += MediaItem(
                id = "ig-c-$i",
                url = u,
                kind = if (isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
                quality = "第 ${i + 1} 项",
                rank = 70 - i,
                container = if (isVideo) "mp4" else "jpg",
                headers = mapOf("Referer" to "https://www.instagram.com/")
            )
        }

        if (media.isEmpty()) throw ParseException("接口返回空媒体列表", platform)

        return ParserDsl.result(
            platform = platform,
            resolverId = "$id-private",
            sourceUrl = "https://www.instagram.com/p/$code/",
            media = media,
            title = item["caption"]?.jsonObject?.str("text")?.take(60),
            author = item["user"]?.jsonObject?.str("username"),
            cover = candidates?.firstOrNull()?.jsonObject?.str("url"),
            source = ParseSource.LOCAL
        )
    }

    private fun privateJson(
        http: com.clipdown.parser.http.HttpFacade,
        headers: Map<String, String>,
        code: String
    ): String? {
        val url = "https://www.instagram.com/p/$code/?__a=1&__d=dis"
        val resp = runCatching { http.get(url, headers) }.getOrNull()
        val b = resp?.body ?: return null
        return if (resp.isSuccessful && b.trimStart().startsWith("{")) b else null
    }

    private fun parseEmbed(html: String, sourceUrl: String, code: String): ParseResult {
        val media = mutableListOf<MediaItem>()
        val igHeaders = mapOf("Referer" to "https://www.instagram.com/")

        HtmlUtil.jsonField(html, "video_url").firstOrNull()?.let { u ->
            media += MediaItem(
                id = "ig-e-v",
                url = u,
                kind = MediaKind.VIDEO,
                quality = "原画质",
                rank = 100,
                container = "mp4",
                mimeType = "video/mp4",
                headers = igHeaders
            )
        }

        val poster = Regex("""<video[^>]+poster=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let { HtmlUtil.unescapeHtmlOf(it) }
        val images = linkedSetOf<String>()
        Regex("""<img[^>]+class="[^"]*EmbeddedMediaImage[^"]*"[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { images += HtmlUtil.unescapeHtmlOf(it.groupValues[1]) }
        HtmlUtil.jsonField(html, "display_url").forEach { images += it }
        poster?.let { images += it }

        images.forEachIndexed { i, u ->
            media += MediaItem(
                id = "ig-e-i$i",
                url = u,
                kind = MediaKind.IMAGE,
                quality = if (i == 0) "原图" else "图片 ${i + 1}",
                rank = 90 - i,
                container = "jpg",
                headers = igHeaders
            )
        }

        if (media.isEmpty()) return ParserDsl.result(platform, "$id-embed", sourceUrl, emptyList())

        val title = HtmlUtil.meta(html, "og:title") ?: HtmlUtil.title(html)
        val author = HtmlUtil.jsonField(html, "username").firstOrNull()
            ?: Regex("""instagram\.com/([A-Za-z0-9_.]+)/""").find(html)?.groupValues?.getOrNull(1)

        return ParserDsl.result(
            platform = platform,
            resolverId = "$id-embed",
            sourceUrl = sourceUrl,
            media = media,
            title = title,
            author = author,
            cover = poster ?: images.firstOrNull(),
            source = ParseSource.OFFICIAL,
            warning = if (media.none { it.kind == MediaKind.VIDEO } && images.size <= 1)
                "多图作品需要登录态才能全部获取" else null
        )
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
}

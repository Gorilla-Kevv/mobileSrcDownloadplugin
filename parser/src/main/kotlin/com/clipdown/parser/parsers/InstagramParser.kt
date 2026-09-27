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

        // 1) 登录态直连：__a=1 老接口已废，改为带 Cookie 抓帖子页 HTML，提取 og:video / video_url / sidecar
        if (!cookie.isNullOrBlank()) {
            val r = runCatching { parsePrivateApi(code, headers + mapOf("Cookie" to cookie), ctx) }
            if (r.isSuccess) return r.getOrThrow().copy(source = ParseSource.LOCAL)
            ctx.log(id, "登录态解析失败：${r.exceptionOrNull()?.message}")
        }
        // 2) 免登录 embed
        var embedFallback = ParserDsl.result(platform, id, url, emptyList())
        val embedUrl = "https://www.instagram.com/p/$code/embed/captioned/"
        val embedHtml = runCatching { ctx.http.get(embedUrl, headers).body }.getOrNull()
        if (!embedHtml.isNullOrBlank()) {
            val parsed = parseEmbed(embedHtml, url, code)
            if (parsed.media.any { it.kind == MediaKind.VIDEO }) return parsed
            if (!parsed.isEmpty) {
                // embed 只有图片时，先别急着返回——WebView 渲染页可能拿到视频（见 2.5）
                embedFallback = parsed
            }
        }

        // 2.5) WebView 渲染抓取（App 层注入；带登录态 Cookie 渲染 reel 页，HTML 内含视频数据）
        val rendered = runCatching {
            ctx.webFetcher?.invoke("https://www.instagram.com/reel/$code/")
                ?: ctx.webFetcher?.invoke("https://www.instagram.com/p/$code/")
        }.getOrNull()
        if (!rendered.isNullOrBlank()) {
            val fromPage = extractFromPageHtml(rendered, url, code)
            if (!fromPage.isEmpty) return fromPage
        }
        if (!embedFallback.isEmpty) return embedFallback

        // 3) oEmbed 兜底
        val oembed = runCatching {
            ctx.http.get("https://api.instagram.com/oembed/?url=$url", headers).body
        }.getOrNull()
        if (!oembed.isNullOrBlank()) {
            val thumbnail = HtmlUtil.jsonField(oembed, "thumbnail_url").firstOrNull()
            val author = HtmlUtil.jsonField(oembed, "author_name").firstOrNull()
            val title = HtmlUtil.jsonField(oembed, "title").firstOrNull()
            if (thumbnail != null) {
                    val ext = thumbnail.substringBefore('?').substringAfterLast('.', "jpg")
                        .takeIf { it.length in 3..4 } ?: "jpg"
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
                                container = ext,
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
        val cookie = headers["Cookie"] ?: ""
        val pageHeaders = mapOf(
            "User-Agent" to ctx.ua(desktop = true),
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "Referer" to "https://www.instagram.com/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Cookie" to cookie
        )
        // 1) 取帖子页只为拿 LSD 令牌（2026 起 IG 对非浏览器客户端返回 JS 空壳页，媒体不在 HTML 里）
        val page = runCatching { ctx.http.get("https://www.instagram.com/p/$code/", pageHeaders) }.getOrNull()
            ?: throw ParseException("无法获取帖子页面", platform)
        val html = page.body ?: throw ParseException("帖子页面返回为空", platform)
        val lsd = Regex(""""LSD",\[\],\{"token":"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex(""""lsd":"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
        if (lsd.isNullOrBlank()) {
            ctx.log(id, "LSD 提取失败：code=${page.code} len=${html.length}")
            throw ParseException("页面缺少 LSD 令牌", platform)
        }

        // 2) 网页版 GraphQL：doc_id 对应 PolarisPostActionLoadPostQuery（shortcode → 媒体）
        val csrf = Regex("csrftoken=([^;]+)").find(cookie)?.groupValues?.getOrNull(1) ?: ""
        val variables = """{"shortcode":"$code","fetch_tagged_user_count":null,"hoisted_comment_id":null,"hoisted_reply_id":null}"""
        val form = "lsd=${urlEncode(lsd)}&variables=${urlEncode(variables)}&doc_id=8845758582119845&server_timestamps=true"
        val apiHeaders = mapOf(
            "User-Agent" to ctx.ua(desktop = true),
            "X-IG-App-ID" to "936619743392459",
            "X-CSRF-Token" to csrf,
            "X-FB-LSD" to lsd,
            "X-ASBD-ID" to "129477",
            "Referer" to "https://www.instagram.com/p/$code/",
            "Cookie" to cookie
        )
        val resp = runCatching { ctx.http.postForm("https://www.instagram.com/api/graphql", form, apiHeaders) }.getOrNull()
            ?: throw ParseException("GraphQL 请求失败", platform)
        val body = resp.body ?: throw ParseException("GraphQL 返回为空", platform)
        if (!resp.isSuccessful) {
            ctx.log(id, "GraphQL code=${resp.code} head=${body.take(140).replace(Regex("\\s+"), " ")}")
            throw ParseException("GraphQL 返回 ${resp.code}", platform)
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            ctx.log(id, "GraphQL 非 JSON：${body.take(140).replace(Regex("\\s+"), " ")}")
            throw ParseException("GraphQL 返回异常格式", platform)
        }
        val node = root["data"]?.jsonObject?.get("xdt_shortcode_media")?.jsonObject
            ?: run {
                ctx.log(id, "GraphQL 无 xdt_shortcode_media：${body.take(160).replace(Regex("\\s+"), " ")}")
                throw ParseException("GraphQL 无媒体数据", platform)
            }

        val igHeaders = mapOf("Referer" to "https://www.instagram.com/")
        val media = mutableListOf<MediaItem>()

        fun addFrom(node: JsonObject, idPrefix: String, rank: Int) {
            (node["video_versions"] as? JsonArray)?.firstOrNull()?.jsonObject?.str("url")?.let { u ->
                media += MediaItem(
                    id = "$idPrefix-v",
                    url = u,
                    kind = MediaKind.VIDEO,
                    quality = "${node["video_versions"]?.jsonArray?.firstOrNull()?.jsonObject?.int("height") ?: 0}p",
                    rank = rank,
                    container = "mp4",
                    mimeType = "video/mp4",
                    headers = igHeaders
                )
            } ?: (node["image_versions2"]?.jsonObject?.get("candidates") as? JsonArray)?.firstOrNull()
                ?.jsonObject?.str("url")?.let { u ->
                    media += MediaItem(
                        id = "$idPrefix-i",
                        url = u,
                        kind = MediaKind.IMAGE,
                        quality = "原图",
                        rank = rank - 20,
                        container = "jpg",
                        headers = igHeaders
                    )
                }
        }

        addFrom(node, "ig-g-0", 100)
        (node["edge_sidecar_to_children"]?.jsonObject?.get("edges") as? JsonArray)?.forEachIndexed { i, el ->
            el.jsonObject["node"]?.jsonObject?.let { addFrom(it, "ig-g-s$i", 70 - i) }
        }

        if (media.isEmpty()) throw ParseException("登录态 GraphQL 未提取到媒体", platform)

        return ParserDsl.result(
            platform = platform,
            resolverId = "$id-private",
            sourceUrl = "https://www.instagram.com/p/$code/",
            media = media,
            title = node["caption"]?.jsonObject?.str("text")?.take(60)
                ?: (node["edge_media_to_caption"]?.jsonObject?.get("edges") as? JsonArray)
                    ?.firstOrNull()?.jsonObject?.get("node")?.jsonObject?.str("text")?.take(60),
            author = node["owner"]?.jsonObject?.str("username"),
            cover = (node["image_versions2"]?.jsonObject?.get("candidates") as? JsonArray)
                ?.firstOrNull()?.jsonObject?.str("url"),
            source = ParseSource.LOCAL
        )
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** 渲染后的帖子页 HTML 提取（WebView 通道）：og:video / video_url / playable_url / display_url */
    private fun extractFromPageHtml(html: String, sourceUrl: String, code: String): ParseResult {
        val igHeaders = mapOf("Referer" to "https://www.instagram.com/")
        val media = mutableListOf<MediaItem>()

        val videos = linkedSetOf<String>()
        HtmlUtil.jsonField(html, "playable_url_quality_hd").firstOrNull()?.let { videos.add(it) }
        HtmlUtil.jsonField(html, "video_url").forEach { videos.add(it) }
        HtmlUtil.jsonField(html, "playable_url").forEach { videos.add(it) }
        Regex("""<meta property="og:video" content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)?.let {
            videos.add(HtmlUtil.unescapeHtmlOf(it.groupValues[1]))
        }
        videos.filter { it.startsWith("http") }.forEachIndexed { i, u ->
            media += MediaItem(
                id = "ig-w-v-$i",
                url = u,
                kind = MediaKind.VIDEO,
                quality = if (i == 0) "原画质" else "备选 ${i + 1}",
                rank = 100 - i,
                container = "mp4",
                mimeType = "video/mp4",
                headers = igHeaders
            )
        }

        val images = linkedSetOf<String>()
        HtmlUtil.jsonField(html, "display_url").forEach { images.add(it) }
        Regex("""<meta property="og:image" content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)?.let {
            images.add(HtmlUtil.unescapeHtmlOf(it.groupValues[1]))
        }
        images.filter { it.startsWith("http") }.take(4).forEachIndexed { i, u ->
            media += MediaItem(
                id = "ig-w-i-$i",
                url = u,
                kind = MediaKind.IMAGE,
                quality = if (i == 0) "原图" else "图片 ${i + 1}",
                rank = 80 - i,
                container = "jpg",
                headers = igHeaders
            )
        }

        if (media.isEmpty()) return ParserDsl.result(platform, "$id-page", sourceUrl, emptyList())

        return ParserDsl.result(
            platform = platform,
            resolverId = "$id-page",
            sourceUrl = sourceUrl,
            media = media,
            title = HtmlUtil.meta(html, "og:title")?.take(60),
            author = HtmlUtil.jsonField(html, "username").firstOrNull()
                ?: Regex("""instagram\.com/([A-Za-z0-9_.]+)/""").find(html)?.groupValues?.getOrNull(1),
            cover = images.firstOrNull(),
            source = ParseSource.LOCAL
        )
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

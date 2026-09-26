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

/**
 * Facebook 解析器。
 *
 * Facebook 的反爬最严格，绝大多数视频需要登录态 Cookie 才能拿到 `hd_src_no_ratelimit`。
 * 因此本解析器采用"移动版页面 + 多字段正则"的思路，并按优先级收集：
 * hd_src_no_ratelimit > hd_src > sd_src_no_ratelimit > sd_src > playable_url > og:video。
 *
 * 未携带 Cookie 时通常只能拿到 og:image 或低清预览，此时返回带 warning 的结果，
 * 由 UI 引导用户补充 Cookie 或开启远端解析。
 */
class FacebookParser : PlatformParser {

    override val platform: Platform = Platform.FACEBOOK
    override val id: String = "fb-mbasic-v1"

    override fun canHandle(url: String): Boolean = true

    override fun parse(url: String, ctx: ParseContext): ParseResult {
        val cookie = ctx.cookieProvider(platform)
        val fbHeaders = ctx.headersFor(platform, desktop = false) +
            mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9"
            ) + (if (!cookie.isNullOrBlank()) mapOf("Cookie" to cookie) else emptyMap())

        // 优先移动版页面：DOM 更精简，且常直接内联视频源字段
        val mobileUrl = toMobile(url)
        val html = runCatching { ctx.http.get(mobileUrl, fbHeaders).body }.getOrNull()
            ?: runCatching { ctx.http.get(url, fbHeaders).body }.getOrNull()
            ?: throw ParseException("无法获取页面内容", platform)

        val videos = extractVideos(html)
        val images = extractImages(html)

        val title = HtmlUtil.meta(html, "og:title")
            ?: HtmlUtil.title(html)?.substringBefore(" | Facebook")
        val cover = HtmlUtil.meta(html, "og:image") ?: images.firstOrNull()
        val author = HtmlUtil.meta(html, "og:site_name") ?: HtmlUtil.meta(html, "article:author")
        val desc = HtmlUtil.meta(html, "og:description")

        val media = buildList {
            videos.forEachIndexed { i, v ->
                add(
                    MediaItem(
                        id = "fb-v-$i",
                        url = v,
                        kind = MediaKind.VIDEO,
                        quality = if (i == 0) "高清" else "标清 ${i}",
                        rank = 100 - i * 10,
                        container = "mp4",
                        mimeType = "video/mp4",
                        headers = mapOf("Referer" to "https://www.facebook.com/"),
                        fileNameHint = title
                    )
                )
            }
            images.take(12).forEachIndexed { i, u ->
                add(
                    MediaItem(
                        id = "fb-i-$i",
                        url = u,
                        kind = MediaKind.IMAGE,
                        quality = "图片 ${i + 1}",
                        rank = 60 - i,
                        container = "jpg",
                        headers = mapOf("Referer" to "https://www.facebook.com/")
                    )
                )
            }
        }

        if (media.isEmpty()) {
            throw ParseException(
                if (cookie.isNullOrBlank())
                    "Facebook 视频需要登录态，请在设置中补充 Cookie 或开启远端解析服务"
                else "未能从页面中解析出媒体资源（Cookie 可能已失效或页面结构已更新）",
                platform
            )
        }

        return ParserDsl.result(
            platform = platform,
            resolverId = id,
            sourceUrl = url,
            media = media,
            title = title,
            author = author,
            cover = cover,
            source = ParseSource.LOCAL,
            warning = if (videos.isEmpty()) "仅获取到图片，视频资源需要登录态" else null
        ).copy(description = desc)
    }

    /** 把桌面链接改写为移动版，降低页面复杂度与风控概率 */
    private fun toMobile(url: String): String = url
        .replace("https://www.facebook.com/", "https://m.facebook.com/")
        .replace("https://web.facebook.com/", "https://m.facebook.com/")
        .let { if (it.contains("m.facebook.com") || it.contains("mbasic")) it else it }

    private fun extractVideos(html: String): List<String> {
        val out = linkedSetOf<String>()
        listOf(
            "hd_src_no_ratelimit", "hd_src", "sd_src_no_ratelimit", "sd_src",
            "playable_url", "playable_url_quality_hd", "video_url"
        ).forEach { key ->
            HtmlUtil.jsonField(html, key).forEach { v ->
                if (v.startsWith("http")) out.add(v)
            }
        }
        HtmlUtil.meta(html, "og:video:url")?.let { out.add(it) }
        HtmlUtil.meta(html, "og:video")?.let { out.add(it) }
        Regex("""(https://video\.f[a-z0-9\-.]*\.fbcdn\.net/[^"'\s\\]+?\.mp4[^"'\s\\]*)""")
            .findAll(html).forEach { out.add(it.groupValues[1]) }
        return out.distinct()
    }

    private fun extractImages(html: String): List<String> {
        val out = linkedSetOf<String>()
        HtmlUtil.meta(html, "og:image")?.let { out.add(it.substringBefore("?")) }
        HtmlUtil.jsonField(html, "uri").forEach { if (it.startsWith("http")) out.add(it.substringBefore("?")) }
        Regex("""(https://scontent\.f[a-z0-9\-.]*\.fbcdn\.net/[^"'\s\\]+?\.(?:jpg|png))""")
            .findAll(html).forEach { out.add(it.groupValues[1]) }
        return out.distinct()
    }
}

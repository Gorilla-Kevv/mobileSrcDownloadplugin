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
 * 微博解析器。
 *
 * 移动版详情页 `https://m.weibo.cn/detail/{id}` 会把整条微博以 JSON 形式内联在
 * `var $render_data = [{...}]` 中，无需登录即可读取：
 * - 视频：`page_info.urls.mp4_720p_mp4 / mp4_hd_mp4 / mp4_sd_mp4`，或 `page_info.media_info.*`
 * - 图片：`pics[].large.url` 或 `pic_infos[]{}.large.url`
 */
class WeiboParser : PlatformParser {

    override val platform: Platform = Platform.WEIBO
    override val id: String = "weibo-mobile-v1"

    private val idRegex = Regex("""/(?:detail|status)(?:es)?[/=](\d{6,25})""")

    override fun canHandle(url: String): Boolean = idRegex.containsMatchIn(url)

    override fun parse(url: String, ctx: ParseContext): ParseResult {
        val id = idRegex.find(url)?.groupValues?.getOrNull(1)
            ?: throw ParseException("无法从链接中提取微博 ID", platform, retryable = false)

        val headers = ctx.headersFor(platform, desktop = false) + mapOf(
            "Referer" to "https://m.weibo.cn/",
            "Accept" to "application/json, text/plain, */*",
            "MWeibo-Pwa" to "1",
            "X-Requested-With" to "XMLHttpRequest"
        )

        // 通道 1：JSON 接口
        val apiBody = runCatching { ctx.http.get("https://m.weibo.cn/statuses/show?id=$id", headers).body }
            .getOrNull()
        if (!apiBody.isNullOrBlank() && apiBody.trimStart().startsWith("{")) {
            val r = runCatching { build(apiBody, url) }.getOrNull()
            if (r != null && !r.isEmpty) return r.copy(resolverId = "$id-api")
        }

        // 通道 2：详情页内联数据
        val html = runCatching { ctx.http.get("https://m.weibo.cn/detail/$id", headers).body }.getOrNull()
            ?: throw ParseException("无法获取微博内容", platform)
        val blob = HtmlUtil.inlineJson(html, "\$render_data") ?: html

        val result = build(blob, url)
        if (result.isEmpty) throw ParseException("未能解析出媒体资源（该微博可能不含媒体或需要登录）", platform)
        return result
    }

    private fun build(blob: String, url: String): ParseResult {
        val videos = linkedSetOf<String>()
        listOf("mp4_720p_mp4", "mp4_hd_mp4", "mp4_sd_mp4", "stream_url", "stream_url_hd", "mp4_1080p_mp4")
            .forEach { key -> HtmlUtil.jsonField(blob, key).forEach { if (it.startsWith("http")) videos.add(it) } }
        Regex("""(https?://[a-z0-9.\-]*\.(?:sina|weibo|weibocdn|sinajs)[a-z0-9.\-]*/[^"'\s\\]+?\.(?:mp4|m3u8)[^"'\s\\]*)""")
            .findAll(blob).forEach { videos.add(it.groupValues[1]) }

        val images = linkedSetOf<String>()
        Regex("""(https?://wx\d?\.sinaimg\.cn/[^"'\s\\]+?\.(?:jpg|png|gif|webp))""")
            .findAll(blob).forEach { images.add(it.groupValues[1]) }
        HtmlUtil.jsonField(blob, "url").forEach {
            if (it.contains("sinaimg.cn") && it.startsWith("http")) images.add(it.substringBefore("?"))
        }

        val media = buildList {
            videos.forEachIndexed { i, v ->
                add(
                    MediaItem(
                        id = "wb-v-$i",
                        url = v,
                        kind = MediaKind.VIDEO,
                        quality = if (i == 0) "高清" else "流畅 ${i + 1}",
                        rank = 100 - i * 5,
                        container = "mp4",
                        mimeType = "video/mp4",
                        headers = mapOf("Referer" to "https://m.weibo.cn/")
                    )
                )
            }
            images.forEachIndexed { i, u ->
                add(
                    MediaItem(
                        id = "wb-i-$i",
                        url = u,
                        kind = MediaKind.IMAGE,
                        quality = "原图 ${i + 1}",
                        rank = 80 - i,
                        container = u.substringAfterLast('.').substringBefore('?'),
                        headers = mapOf("Referer" to "https://m.weibo.cn/")
                    )
                )
            }
        }

        val title = HtmlUtil.jsonField(blob, "text").firstOrNull()
            ?.let { Regex("<[^>]+>").replace(it, " ") }
            ?.replace(Regex("""\s+"""), " ")?.trim()?.take(60)
        val author = HtmlUtil.jsonField(blob, "screen_name").firstOrNull()
            ?: HtmlUtil.jsonField(blob, "nickname").firstOrNull()

        return ParserDsl.result(
            platform = platform,
            resolverId = id,
            sourceUrl = url,
            media = media,
            title = title,
            author = author,
            cover = images.firstOrNull() ?: HtmlUtil.jsonField(blob, "cover").firstOrNull(),
            source = ParseSource.LOCAL
        )
    }
}

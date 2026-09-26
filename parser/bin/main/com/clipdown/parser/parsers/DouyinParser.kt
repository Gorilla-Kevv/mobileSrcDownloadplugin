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
 * 抖音解析器。
 *
 * 双通道：
 * 1. 旧版分享接口 `https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids={id}`，
 *    返回干净 JSON，成功率最高，但随时可能被下线；
 * 2. 详情页内联 `window._ROUTER_DATA.loaderData.video_(id)/page.videoInfoRes.item_list[0]`，
 *    该结构含 `video.play_addr.url_list`（无水印，需把 playwm 替换为 play）。
 */
class DouyinParser : PlatformParser {

    override val platform: Platform = Platform.DOUYIN
    override val id: String = "douyin-local-v1"

    private val idRegex = Regex("""/(?:video|note|slides)/(\d{6,25})""")

    override fun canHandle(url: String): Boolean = true

    override fun parse(url: String, ctx: ParseContext): ParseResult {
        val awemeId = idRegex.find(url)?.groupValues?.getOrNull(1)
        val headers = ctx.headersFor(platform, desktop = false) + mapOf(
            "Referer" to "https://www.douyin.com/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        // 通道 1：旧版分享接口
        if (awemeId != null) {
            val api = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$awemeId"
            val body = runCatching { ctx.http.get(api, headers).body }.getOrNull()
            if (!body.isNullOrBlank() && body.contains("item_list")) {
                val parsed = fromItemInfo(body, url)
                if (!parsed.isEmpty) return parsed
            }
        }

        // 通道 2：详情页内联数据
        val html = runCatching { ctx.http.get(url, headers).body }.getOrNull()
            ?: throw ParseException("无法获取页面内容（抖音可能要求验证）", platform)
        val blob = HtmlUtil.inlineJson(html, "_ROUTER_DATA")
            ?: HtmlUtil.inlineJson(html, "RENDER_DATA") ?: html

        val videos = collectVideos(blob, html)
        val images = collectImages(blob, html)
        val title = HtmlUtil.jsonField(blob, "desc").firstOrNull()
            ?: HtmlUtil.meta(html, "og:title")?.substringBefore(" - 抖音")
        val author = HtmlUtil.jsonField(blob, "nickname").firstOrNull()
        val cover = HtmlUtil.meta(html, "og:image") ?: HtmlUtil.jsonField(blob, "cover").firstOrNull()

        val media = buildList {
            videos.forEachIndexed { i, v ->
                add(
                    MediaItem(
                        id = "dy-v-$i",
                        url = v,
                        kind = MediaKind.VIDEO,
                        quality = if (i == 0) "无水印" else "备选 ${i + 1}",
                        rank = 100 - i,
                        container = "mp4",
                        mimeType = "video/mp4",
                        headers = mapOf("Referer" to "https://www.douyin.com/"),
                        fileNameHint = title
                    )
                )
            }
            images.forEachIndexed { i, u ->
                add(
                    MediaItem(
                        id = "dy-i-$i",
                        url = u,
                        kind = MediaKind.IMAGE,
                        quality = "原图 ${i + 1}",
                        rank = 80 - i,
                        container = "jpeg",
                        headers = mapOf("Referer" to "https://www.douyin.com/")
                    )
                )
            }
        }

        if (media.isEmpty()) throw ParseException("未能解析出媒体资源（可能需要登录态或页面结构已更新）", platform)

        return ParserDsl.result(
            platform = platform,
            resolverId = id,
            sourceUrl = url,
            media = media,
            title = title,
            author = author,
            cover = cover,
            source = ParseSource.LOCAL
        )
    }

    private fun fromItemInfo(body: String, url: String): ParseResult {
        val videos = HtmlUtil.jsonField(body, "url_list").flatMap { raw ->
            raw.trim('[', ']').split(',').map { it.trim().trim('"') }
        }.map { it.replace("playwm", "play") }
            .filter { it.startsWith("http") }
            .distinct()

        val images = HtmlUtil.jsonField(body, "url_list").flatMap { raw ->
            raw.trim('[', ']').split(',').map { it.trim().trim('"') }
        }.filter { it.startsWith("http") && it.contains("douyinpic") }.distinct()

        val title = HtmlUtil.jsonField(body, "desc").firstOrNull()
        val author = HtmlUtil.jsonField(body, "nickname").firstOrNull()
        val cover = HtmlUtil.jsonField(body, "cover").firstOrNull()
            ?: HtmlUtil.jsonField(body, "origin_cover").firstOrNull()

        val media = buildList {
            videos.forEachIndexed { i, v ->
                add(
                    MediaItem(
                        id = "dy-api-v-$i",
                        url = v,
                        kind = MediaKind.VIDEO,
                        quality = if (i == 0) "无水印" else "备用源",
                        rank = 100 - i,
                        container = "mp4",
                        mimeType = "video/mp4",
                        headers = mapOf("Referer" to "https://www.douyin.com/")
                    )
                )
            }
            images.forEachIndexed { i, u ->
                add(
                    MediaItem(
                        id = "dy-api-i-$i",
                        url = u,
                        kind = MediaKind.IMAGE,
                        quality = "原图 ${i + 1}",
                        rank = 70 - i,
                        container = "jpeg"
                    )
                )
            }
        }

        return ParserDsl.result(
            platform = platform,
            resolverId = "$id-api",
            sourceUrl = url,
            media = media,
            title = title,
            author = author,
            cover = cover,
            source = ParseSource.OFFICIAL
        )
    }

    private fun collectVideos(blob: String, html: String): List<String> {
        val out = linkedSetOf<String>()
        HtmlUtil.jsonField(blob, "url_list").flatMap { raw ->
            raw.trim('[', ']').split(',').map { it.trim().trim('"') }
        }.forEach { out.add(it.replace("playwm", "play")) }
        HtmlUtil.jsonField(blob, "play_addr").forEach { out.add(it) }
        Regex("""(https://[a-z0-9.\-]*douyinvod\.com/[^"'\s\\]+?\.(?:mp4|m3u8)[^"'\s\\]*)""")
            .findAll(blob).forEach { out.add(it.groupValues[1]) }
        HtmlUtil.findVideoSources(html).forEach { out.add(it) }
        return out.filter { it.startsWith("http") }.distinct()
    }

    private fun collectImages(blob: String, html: String): List<String> {
        val out = linkedSetOf<String>()
        Regex("""(https://p\d?[a-z0-9.\-]*douyinpic\.com/[^"'\s\\]+?)(\?[^"'\s\\]*)?""")
            .findAll(blob).forEach { out.add(it.groupValues[1]) }
        Regex("""(https://[a-z0-9.\-]*douyinpic\.com/[^"'\s\\]+?)(\?[^"'\s\\]*)?""")
            .findAll(html).forEach { out.add(it.groupValues[1]) }
        return out.distinct().take(20)
    }
}

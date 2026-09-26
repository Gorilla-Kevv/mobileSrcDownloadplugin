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
 * TikTok 解析器。
 *
 * 数据来自详情页内联的 `window.__UNIVERSAL_DATA_FOR_REHYDRATION__`，
 * 其中 `webapp.video-detail.itemInfo.itemStruct` 承载完整媒体信息：
 * - video.playAddr / downloadAddr：带水印直链
 * - video.bitRateInfo[]：多档码率直链（多数情况下为无水印源）
 * - imagePost.images[]：图文作品
 */
class TiktokParser : PlatformParser {

    override val platform: Platform = Platform.TIKTOK
    override val id: String = "tiktok-local-v1"

    override fun canHandle(url: String): Boolean = true

    override fun parse(url: String, ctx: ParseContext): ParseResult {
        val headers = ctx.headersFor(platform) + mapOf(
            "Referer" to "https://www.tiktok.com/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val html = runCatching { ctx.http.get(url, headers).body }.getOrNull()
            ?: throw ParseException("无法获取页面内容（可能触发了风控或地区限制）", platform)

        val blob = HtmlUtil.inlineJson(html, "__UNIVERSAL_DATA_FOR_REHYDRATION__")
            ?: HtmlUtil.inlineJson(html, "SIGI_STATE") ?: html

        val videos = collectVideos(blob, html)
        val images = collectImages(blob, html)

        val title = HtmlUtil.meta(html, "og:title")
            ?: HtmlUtil.jsonField(blob, "desc").firstOrNull()
        val author = HtmlUtil.jsonField(blob, "uniqueId").firstOrNull()
            ?: HtmlUtil.meta(html, "og:site_name")
        val cover = HtmlUtil.meta(html, "og:image") ?: HtmlUtil.jsonField(blob, "cover").firstOrNull()

        val media = buildList {
            videos.forEachIndexed { i, v ->
                add(
                    MediaItem(
                        id = "tt-v-$i",
                        url = v,
                        kind = MediaKind.VIDEO,
                        quality = if (i == 0) "最佳画质" else "备选 ${i + 1}",
                        rank = 100 - i,
                        container = "mp4",
                        mimeType = "video/mp4",
                        headers = mapOf("Referer" to "https://www.tiktok.com/"),
                        fileNameHint = title
                    )
                )
            }
            images.forEachIndexed { i, u ->
                add(
                    MediaItem(
                        id = "tt-i-$i",
                        url = u,
                        kind = MediaKind.IMAGE,
                        quality = "原图 ${i + 1}",
                        rank = 80 - i,
                        container = "webp",
                        headers = mapOf("Referer" to "https://www.tiktok.com/")
                    )
                )
            }
        }

        if (media.isEmpty()) throw ParseException("未能解析出媒体资源（TikTok 风控或页面结构已更新）", platform)

        return ParserDsl.result(
            platform = platform,
            resolverId = id,
            sourceUrl = url,
            media = media,
            title = title,
            author = author,
            cover = cover,
            source = ParseSource.LOCAL,
            warning = if (images.isNotEmpty() && videos.isEmpty()) "图文作品" else null
        )
    }

    private fun collectVideos(blob: String, html: String): List<String> {
        val out = linkedSetOf<String>()
        // bitRateInfo 内的 PlayAddr.Url 通常是最高质量且无水印
        HtmlUtil.jsonField(blob, "PlayAddr").forEach { raw ->
            Regex("""Url\\?"\s*:\s*\\?"([^"\\]+)""").find(raw)?.groupValues?.getOrNull(1)?.let { out.add(it) }
            if (raw.startsWith("http")) out.add(raw)
        }
        listOf("playAddr", "downloadAddr", "playUrl", "downloadUrl").forEach { key ->
            HtmlUtil.jsonField(blob, key).forEach { if (it.startsWith("http")) out.add(it) }
        }
        Regex("""(https://[a-z0-9.\-]*tiktokcdn[a-z0-9.\-]*/[^"'\s\\]+?\.mp4[^"'\s\\]*)""")
            .findAll(blob).forEach { out.add(it.groupValues[1]) }
        HtmlUtil.findVideoSources(html).forEach { out.add(it) }
        return out.map { it.replace("\\u002F", "/") }.filter { it.startsWith("http") }.distinct()
    }

    private fun collectImages(blob: String, html: String): List<String> {
        val out = linkedSetOf<String>()
        HtmlUtil.jsonField(blob, "imageURL").forEach { if (it.startsWith("http")) out.add(it) }
        Regex("""(https://p\d+-sign\.tiktokcdn[^"'\s\\]+?\.(?:jpeg|webp|png)[^"'\s\\]*)""")
            .findAll(blob).forEach { out.add(it.groupValues[1]) }
        Regex("""(https://[a-z0-9.\-]*tiktokcdn[a-z0-9.\-]*/[^"'\s\\]*obj/tos[^"'\s\\]*)""")
            .findAll(html).forEach { out.add(it.groupValues[1]) }
        return out.distinct().take(20)
    }
}

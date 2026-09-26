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
 * 通用网页解析器（兜底）。
 *
 * 面向未在白名单内、但页面本身开放了 OpenGraph / 直链的站点。
 * 采集顺序：og:video(:url) → twitter:player → <video>/<source> → JSON 中的 mp4/m3u8 → og:image。
 */
class GenericParser : PlatformParser {

    override val platform: Platform = Platform.GENERIC
    override val id: String = "generic-og-v1"

    override fun canHandle(url: String): Boolean = true

    override fun parse(url: String, ctx: ParseContext): ParseResult {
        val headers = ctx.baseHeaders() + mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val resp = runCatching { ctx.http.get(url, headers) }
            .getOrElse { throw ParseException("网络请求失败：${it.message}", Platform.GENERIC) }
        val html = resp.body ?: throw ParseException("页面内容为空", Platform.GENERIC)

        val media = mutableListOf<MediaItem>()

        listOfNotNull(
            HtmlUtil.meta(html, "og:video:url"),
            HtmlUtil.meta(html, "og:video"),
            HtmlUtil.meta(html, "twitter:player:stream")
        ).forEachIndexed { i, u ->
            media += MediaItem(
                id = "og-v-$i",
                url = u,
                kind = if (u.contains(".m3u8")) MediaKind.VIDEO else MediaKind.VIDEO,
                quality = "页面声明的媒体",
                rank = 100 - i,
                container = if (u.contains(".m3u8")) "m3u8" else "mp4",
                isPlaylist = u.contains(".m3u8")
            )
        }

        if (media.isEmpty()) {
            HtmlUtil.findVideoSources(html).take(3).forEachIndexed { i, u ->
                media += MediaItem(
                    id = "dom-v-$i",
                    url = u,
                    kind = MediaKind.VIDEO,
                    quality = "页面内嵌媒体 ${i + 1}",
                    rank = 90 - i,
                    container = if (u.contains(".m3u8")) "m3u8" else "mp4",
                    isPlaylist = u.contains(".m3u8")
                )
            }
        }

        val images = linkedSetOf<String>()
        HtmlUtil.meta(html, "og:image")?.let { images.add(it) }
        HtmlUtil.findImages(html, 8).forEach { images.add(it) }
        images.forEachIndexed { i, u ->
            media += MediaItem(
                id = "og-i-$i",
                url = u,
                kind = MediaKind.IMAGE,
                quality = if (i == 0) "封面" else "图片 $i",
                rank = 60 - i,
                container = "jpg"
            )
        }

        if (media.isEmpty()) throw ParseException("该页面没有可下载的媒体资源", Platform.GENERIC)

        return ParserDsl.result(
            platform = Platform.GENERIC,
            resolverId = id,
            sourceUrl = url,
            media = media,
            title = HtmlUtil.meta(html, "og:title") ?: HtmlUtil.title(html),
            author = HtmlUtil.meta(html, "og:site_name"),
            cover = HtmlUtil.meta(html, "og:image"),
            source = ParseSource.LOCAL,
            warning = "通用解析，清晰度与完整性不保证"
        ).copy(description = HtmlUtil.meta(html, "og:description"))
    }
}

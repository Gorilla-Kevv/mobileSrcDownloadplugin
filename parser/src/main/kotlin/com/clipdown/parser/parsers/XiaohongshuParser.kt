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
 * 小红书解析器。
 *
 * 数据来源：笔记详情页内联的 `window.__INITIAL_STATE__`。
 * 之所以不用严格 JSON 解析：小红书的 SSR 数据里存在 `undefined` 字面量与尾随逗号，
 * 严格解析会整体失败；用字段级正则反而更稳，代价是页面结构大改时需要同步更新正则。
 *
 * 视频：stream.h264[].masterUrl（1080p/720p/480p 依次降级）
 * 图集：imageList[].urlDefault，去掉 `?imageView2...` 裁剪参数即为原图
 */
class XiaohongshuParser : PlatformParser {

    override val platform: Platform = Platform.XIAOHONGSHU
    override val id: String = "xhs-local-v1"

    override fun canHandle(url: String): Boolean = true

    override fun parse(url: String, ctx: ParseContext): ParseResult {
        // WebView 渲染优先：真浏览器栈过阿里云 WAF（OkHttp 指纹被拦，见 PROGRESS 阶段 8）
        val rendered = runCatching { ctx.webFetcher?.invoke(url) }.getOrNull()
        val respBody: String?
        val respCode: Int
        if (!rendered.isNullOrBlank()) {
            respBody = rendered
            respCode = 200
        } else {
            val headers = warmUp(ctx.headersFor(platform), ctx) + mapOf(
                "Referer" to "https://www.xiaohongshu.com/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            val resp = runCatching { ctx.http.get(url, headers) }
                .getOrElse { throw ParseException("网络请求失败：${it.message}", platform) }
            respBody = resp.body
            respCode = resp.code
        }

        ctx.log(
            id,
            "请求诊断：via=${if (!rendered.isNullOrBlank()) "webview" else "okhttp"} " +
                "code=$respCode len=${respBody?.length} state=${respBody?.contains("__INITIAL_STATE__")}"
        )

        val html = respBody
            ?: throw ParseException("页面内容为空（可能触发了风控）", platform)
        if (respCode == 404) throw ParseException("笔记不存在或已删除", platform, retryable = false)
        if (respCode >= 400) throw ParseException("页面返回 $respCode", platform)

        val state = HtmlUtil.inlineJson(html, "__INITIAL_STATE__")
        val blob = state ?: html

        val videos = extractVideos(blob)
        val images = extractImages(blob)

        val title = HtmlUtil.meta(html, "og:title")?.substringBefore(" - 小红书")
            ?: HtmlUtil.jsonField(blob, "displayTitle").firstOrNull()
            ?: HtmlUtil.jsonField(blob, "title").firstOrNull()
        val author = HtmlUtil.jsonField(blob, "nickname").firstOrNull()
        val cover = HtmlUtil.meta(html, "og:image") ?: images.firstOrNull()
        val desc = HtmlUtil.meta(html, "og:description")
            ?: HtmlUtil.jsonField(blob, "desc").firstOrNull()

        val media = buildList {
            videos.forEachIndexed { i, v ->
                add(
                    MediaItem(
                        id = "xhs-v-${v.hashCode().toString(16)}",
                        url = v,
                        kind = MediaKind.VIDEO,
                        quality = if (i == 0) "原画质" else "备用源 ${i + 1}",
                        rank = 100 - i,
                        container = "mp4",
                        mimeType = "video/mp4",
                        headers = mapOf("Referer" to "https://www.xiaohongshu.com/"),
                        fileNameHint = title
                    )
                )
            }
            images.forEachIndexed { i, u ->
                add(
                    MediaItem(
                        id = "xhs-i-${u.hashCode().toString(16)}",
                        url = u,
                        kind = MediaKind.IMAGE,
                        quality = if (videos.isEmpty()) "原图 ${i + 1}" else "封面 ${i + 1}",
                        rank = 90 - i,
                        container = guessExt(u),
                        mimeType = "image/*",
                        headers = mapOf("Referer" to "https://www.xiaohongshu.com/"),
                        fileNameHint = title?.let { "${it}_${i + 1}" }
                    )
                )
            }
        }

        if (media.isEmpty()) {
            throw ParseException(
                if (HtmlUtil.meta(html, "og:description")?.contains("登录", true) == true)
                    "该笔记需要登录态，请在设置中补充小红书 Cookie 或开启远端解析"
                else "未能从页面中提取到媒体资源（页面结构可能已更新）",
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
            warning = if (videos.isEmpty() && images.size <= 1) "仅获取到封面图，完整图集需要登录态" else null
        ).copy(description = desc)
    }

    private fun extractVideos(blob: String): List<String> {
        val out = linkedSetOf<String>()
        HtmlUtil.jsonField(blob, "masterUrl").forEach { out.add(it) }
        HtmlUtil.jsonField(blob, "backupUrls").forEach { raw ->
            raw.trim('[', ']').split(',').forEach { out.add(it.trim().trim('"')) }
        }
        Regex("""(https://sns-video[a-z0-9\-.]*\.xhscdn\.com/[^"'\s\\]+?\.mp4)""")
            .findAll(blob).forEach { out.add(it.groupValues[1]) }
        return out.filter { it.startsWith("http") }.distinct()
    }

    private fun extractImages(blob: String): List<String> {
        val out = linkedSetOf<String>()
        HtmlUtil.jsonField(blob, "urlDefault").forEach { out.add(it.substringBefore("?")) }
        HtmlUtil.jsonField(blob, "urlPre").forEach { out.add(it.substringBefore("?")) }
        Regex("""(https://sns-webpic[a-z0-9\-.]*\.xhscdn\.com/[^"'\s\\]+?)(\?[^"'\s\\]*)?""")
            .findAll(blob).forEach { out.add(it.groupValues[1]) }
        return out.filter { it.startsWith("http") }.distinct().take(18)
    }

    private fun guessExt(u: String): String =
        u.substringBefore('?').substringAfterLast('.', "jpg").takeIf { it.length in 3..4 } ?: "jpg"

    /**
     * WAF 预热：阿里云 WAF 对"无 acw_tc 的新客户端"（OkHttp 指纹）会 302 到风控页。
     * 先访问主页拿 Set-Cookie 的 acw_tc 等种子，合并进 Cookie 后二次请求放行。
     */
    private fun warmUp(headers: Map<String, String>, ctx: ParseContext): Map<String, String> {
        val existing = headers["Cookie"]
        if (!existing.isNullOrBlank() && existing.contains("acw_tc")) return headers
        val warm = runCatching {
            ctx.http.get(
                "https://www.xiaohongshu.com/",
                mapOf(
                    "User-Agent" to (headers["User-Agent"] ?: ctx.ua()),
                    "Accept-Language" to "zh-CN,zh;q=0.9",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Cookie" to (existing ?: "")
                )
            )
        }.onFailure { ctx.log(id, "WAF 预热请求异常：${it.message}") }.getOrNull() ?: return headers
        val seeds = warm.headers.values("Set-Cookie")
            .map { it.substringBefore(';').trim() }
            .filter { it.contains('=') && !it.startsWith("acw_sc__v2=") }
        if (seeds.isEmpty()) {
            ctx.log(id, "WAF 预热无种子：code=${warm.code} len=${warm.body?.length}")
            return headers
        }
        val merged = ((existing?.trimEnd(';')?.plus("; ")) ?: "") + seeds.joinToString("; ")
        ctx.log(id, "WAF 预热(${warm.code})：收集 ${seeds.size} 项种子 Cookie")
        return headers + ("Cookie" to merged)
    }
}

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
 * 视频：stream.h264[].masterUrl（1080p/720p/480p 依次降级）；
 *   **有视频时丢弃 imageList 封面帧**（与 IG 修复 10c 同源，避免"视频+封面图"被判图集）
 * 图集：imageList[].urlDefault（默认画质）；**按文件 ID 去重**——
 *   同图的 urlPre（预览画质）与 urlDefault 文件 ID 相同，按 URL 去重会让每张图出现两次
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

        // 失效笔记守卫：小红书对已删除 / 缺 xsec_token / 失效的笔记并不返回 4xx，
        // 而是渲染一张"你访问的页面不见了"的页或跳到探索页（title="小红书 - 你的生活兴趣社区"）。
        // 这两类页面的 SSR 里塞的是推荐流，没有这道校验会把推荐流的封面图当成图集返回。
        val pageTitle = HtmlUtil.meta(html, "og:title") ?: HtmlUtil.title(html)
        if (pageTitle != null && (
            pageTitle.contains("你访问的页面不见了") || pageTitle.contains("你的生活兴趣社区")
            )
        ) {
            throw ParseException(
                "笔记不存在或链接已失效（小红书返回了失效页/探索页，可尝试重新复制带 xsec_token 的分享链接）",
                platform,
                retryable = false
            )
        }

        val state = HtmlUtil.inlineJson(html, "__INITIAL_STATE__")
        val blob = state ?: html

        // 笔记详情页守卫：小红书对"已删除 / 缺 xsec_token / 失效"的笔记会跳到 /404 或探索页，
        // 页面里塞的是推荐流——没有这道校验会把推荐流的图当成图集返回（用户下到一堆无关图）。
        // 只在拿到 __INITIAL_STATE__ 时校验；没拿到（WAF 拦截页）仍走下面的媒体提取失败分支。
        if (state != null && !state.contains("noteDetailMap")) {
            throw ParseException(
                "链接不是笔记详情页：笔记可能已删除、链接失效或缺少 xsec_token（小红书已跳转到首页/探索页）",
                platform,
                retryable = false
            )
        }

        val videos = extractVideos(blob)
        // 视频笔记的 imageList 是封面帧：与 IG 修复 10c 同源，有视频时图片一律丢弃，
        // 否则 media=[视频, 封面图] → isAlbumMultiSelect=true → 弹图集竖条、跳过自动下载，
        // 用户最终下到"视频 + 封面图"。图文笔记无 masterUrl，不受影响。
        val images = if (videos.isEmpty()) extractImages(blob) else emptyList()

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

    /**
     * 图集提取。
     *
     * 同一张图小红书会同时下发 `urlPre`（预览画质）与 `urlDefault`（默认画质）——
     * 两者只有路径 hash 段和 `!nd_prv_/!nd_dft_` 后缀不同，**文件 ID 相同**。
     * 因此按 URL 字符串去重不够（每张图会产出 2 项），必须按文件 ID 去重并优先保留 urlDefault。
     */
    private fun extractImages(blob: String): List<String> {
        val byFileId = linkedMapOf<String, String>()
        // 先灌 urlDefault：同 ID 先到先得，urlPre 只能补空缺，保证留下来的是更高画质那份
        HtmlUtil.jsonField(blob, "urlDefault").forEach { u ->
            val url = u.substringBefore("?")
            byFileId.putIfAbsent(imageFileId(url), url)
        }
        HtmlUtil.jsonField(blob, "urlPre").forEach { u ->
            val url = u.substringBefore("?")
            byFileId.putIfAbsent(imageFileId(url), url)
        }
        Regex("""(https://sns-webpic[a-z0-9\-.]*\.xhscdn\.com/[^"'\s\\]+?)(\?[^"'\s\\]*)?""")
            .findAll(blob).forEach { m ->
                val url = m.groupValues[1]
                byFileId.putIfAbsent(imageFileId(url), url)
            }
        return byFileId.values.filter { it.startsWith("http") }.distinct().take(18)
    }

    /**
     * 图片去重键：`.../notes_pre_post/<fileId>!nd_dft_wlteh_jpg_3` 中的 <fileId>。
     * 取路径最后一段并去掉 `!` 之后的画质变换后缀。
     */
    private fun imageFileId(url: String): String =
        url.substringBefore("?").substringAfterLast('/').substringBefore('!').ifBlank { url }

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

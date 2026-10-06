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
        // 直连优先：**移动端 UA 的普通 HTTP 请求即可拿到带 imageList 的 SSR 笔记页**
        // （桌面 UA 会 302 到 /login；而 WebView 即使把 UA 伪装成 Android Chrome 也会被跳登录墙，
        //  2026-10 实测三种 UA + WebView 对照）。WebView 退居兜底，用于阿里云 WAF 指纹挑战等场景。
        val headers = warmUp(ctx.headersFor(platform, desktop = false), ctx) + mapOf(
            "Referer" to "https://www.xiaohongshu.com/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val direct = runCatching { ctx.http.get(url, headers) }.getOrNull()
        ctx.log(
            id,
            "直连结果：code=${direct?.code} len=${direct?.body?.length} " +
                "finalUrl=${direct?.finalUrl?.take(120)} imageList=${direct?.body?.contains("\"imageList\"")}"
        )

        var via = "okhttp"
        var respCode = direct?.code ?: 0
        var respBody: String? = direct?.body

        // 直连结果不可用（被 WAF 拦 / 空页 / 无笔记数据）时才走 WebView
        if (respBody.isNullOrBlank() || !respBody.contains("\"imageList\"")) {
            val rendered = runCatching { ctx.webFetcher?.invoke(url) }.getOrNull()
            if (!rendered.isNullOrBlank()) {
                via = "webview"
                respCode = 200
                respBody = rendered
            }
        }

        ctx.log(
            id,
            "请求诊断：via=$via code=$respCode len=${respBody?.length} " +
                "state=${respBody?.contains("__INITIAL_STATE__")} imageList=${respBody?.contains("\"imageList\"")}"
        )

        val html = respBody
            ?: throw ParseException("页面内容为空（可能触发了风控）", platform)
        if (respCode == 404) throw ParseException("笔记不存在或已删除", platform, retryable = false)
        if (respCode >= 400) throw ParseException("页面返回 $respCode", platform)

        // 失效 / 登录墙守卫：小红书对"已删除、缺 xsec_token、失效"的笔记不返回 4xx，
        // 而是渲染「你访问的页面不见了」，或跳探索页；**未登录时还会 302 到 /login，
        // 而登录页的 <title> 与探索页完全相同**（都是"小红书 - 你的生活兴趣社区"，2026-10 实测）。
        // 这两类页面的 SSR 里塞的是推荐流，没有这道校验会把推荐流封面图当成图集返回。
        val pageTitle = HtmlUtil.meta(html, "og:title") ?: HtmlUtil.title(html)
        if (pageTitle != null && pageTitle.contains("你访问的页面不见了")) {
            throw ParseException("笔记不存在或已删除（小红书返回了失效页）", platform, retryable = false)
        }
        if (pageTitle != null && pageTitle.contains("你的生活兴趣社区")) {
            throw ParseException(
                "小红书要求登录态，或该链接已失效（页面被跳到了首页/登录页）。" +
                    "请在「设置」中补充小红书 Cookie 后重试，或重新复制带 xsec_token 的分享链接",
                platform,
                retryable = false
            )
        }

        val state = HtmlUtil.inlineJson(html, "__INITIAL_STATE__")
        val blob = state ?: html

        // 笔记详情页守卫：小红书对"已删除 / 缺 xsec_token / 失效"的笔记会跳到 /404 或探索页，
        // 页面里塞的是推荐流——没有这道校验会把推荐流的图当成图集返回（用户下到一堆无关图）。
        // 只在拿到 __INITIAL_STATE__ 时校验；没拿到（WAF 拦截页）仍走下面的媒体提取失败分支。
        // 注意：移动端页面**没有** noteDetailMap（那是桌面端结构），以 imageList 为准。
        if (state != null && !state.contains("noteDetailMap") && !state.contains("\"imageList\"")) {
            throw ParseException(
                "链接不是笔记详情页：笔记可能已删除、链接失效或缺少 xsec_token（小红书已跳转到首页/探索页）",
                platform,
                retryable = false
            )
        }

        // 移动端与桌面端的 SSR 结构不同（2026-10 实测）：
        //   桌面：noteDetailMap → urlDefault/urlPre、displayTitle、nickname
        //   移动：imageList[].url（含 fileId）、title、user.nickName
        // 以首个 imageList 为中心切出"笔记窗口"，避免把整页推荐流（objectPosition 2/3…）吃进来
        val window = noteWindow(blob)

        val videos = extractVideos(blob)
        // 视频笔记的 imageList 是封面帧：与 IG 修复 10c 同源，有视频时图片一律丢弃，
        // 否则 media=[视频, 封面图] → isAlbumMultiSelect=true → 弹图集竖条、跳过自动下载，
        // 用户最终下到"视频 + 封面图"。图文笔记无 masterUrl，不受影响。
        val images = if (videos.isEmpty()) extractImages(blob, window) else emptyList()

        val title = HtmlUtil.meta(html, "og:title")?.substringBefore(" - 小红书")?.takeIf { it.isNotBlank() }
            ?: HtmlUtil.jsonField(window, "displayTitle").firstOrNull()?.takeIf { it.isNotBlank() }
            ?: HtmlUtil.jsonField(window, "title").firstOrNull()?.takeIf { it.isNotBlank() }
        val author = authorFromWindow(window)
            ?: HtmlUtil.jsonField(blob, "nickname").firstOrNull()?.takeIf { it.isNotBlank() }
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
     * 以首个 `imageList` 为中心切出"笔记窗口"（前 3000 / 后 3000 字符）。
     *
     * 小红书页面里除本笔记外还有推荐流（`objectPosition` 递增的其它笔记），
     * 整页扫描会把推荐流的图一起抓进来。笔记自身的字段分布在 imageList 两侧：
     * `user.nickName` 在其前，`title` 在其后，故取双侧窗口。
     */
    private fun noteWindow(blob: String): String {
        val idx = blob.indexOf("\"imageList\"")
        if (idx < 0) return blob
        val start = (idx - 3000).coerceAtLeast(0)
        val end = (idx + 3000).coerceAtMost(blob.length)
        return blob.substring(start, end)
    }

    /** 取 `imageList` 数组的文本切片（括号配对），避免 jsonField("url") 命中页面其它 url */
    private fun imageListSlice(text: String): String? {
        val m = Regex(""""imageList"\s*:\s*\[""").find(text) ?: return null
        val open = text.indexOf('[', m.range.first)
        if (open < 0) return null
        var depth = 0
        for (p in open until text.length) {
            when (text[p]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return text.substring(open, p + 1)
                }
            }
        }
        return null
    }

    /** 作者：优先取 `"user":{...}` 作用域内的 nickName——页面里 `atUserList` 的 @提及 排在更前面，全页取首个会拿到被提及的人 */
    private fun authorFromWindow(window: String): String? {
        val i = window.indexOf("\"user\":{")
        val scope = if (i >= 0) window.substring(i, (i + 600).coerceAtMost(window.length)) else window
        return HtmlUtil.jsonField(scope, "nickName").firstOrNull()?.takeIf { it.isNotBlank() }
            ?: HtmlUtil.jsonField(scope, "nickname").firstOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 图集提取。
     *
     * 同一张图小红书会同时下发 `urlPre`（预览画质）与 `urlDefault`（默认画质）——
     * 两者只有路径 hash 段和 `!nd_prv_/!nd_dft_` 后缀不同，**文件 ID 相同**。
     * 因此按 URL 字符串去重不够（每张图会产出 2 项），必须按文件 ID 去重并优先保留 urlDefault。
     * 移动端结构则是 `imageList[].url`（后缀 `!h5_1080jpg`），同样带 fileId。
     */
    private fun extractImages(blob: String, window: String): List<String> {
        val byFileId = linkedMapOf<String, String>()
        fun put(u: String) {
            val url = u.substringBefore("?")
            if (url.startsWith("http")) byFileId.putIfAbsent(imageFileId(url), url)
        }

        fun fillStructured(text: String) {
            // 桌面端 urlDefault（默认画质）
            HtmlUtil.jsonField(text, "urlDefault").forEach { put(it) }
            // 移动端 imageList[].url —— 只扫 imageList 数组切片，避免命中页面其它 "url" 字段
            imageListSlice(text)?.let { slice -> HtmlUtil.jsonField(slice, "url").forEach { put(it) } }
            // 预览画质兜底
            HtmlUtil.jsonField(text, "urlPre").forEach { put(it) }
        }

        fun fillRegex(text: String) {
            // DOM / 未转义 URL 兜底：必须用贪婪匹配——惰性 + 可选 query 会把 URL 截断成 `.../2`
            Regex("""(https?://sns-webpic[a-z0-9\-.]*\.xhscdn\.com/[^"'\s\\]+)""")
                .findAll(text).forEach { m -> put(m.groupValues[1]) }
        }

        // 笔记窗口内先走结构化提取（不碰推荐流）；只有在**完全提取不到**时才启用正则兜底——
        // 正则扫页面会把紧随其后的推荐流封面一起吃进来（实测：单图笔记多出 1 张无关图）
        fillStructured(window)
        if (byFileId.isEmpty()) {
            fillRegex(window)
            if (byFileId.isEmpty()) {
                fillStructured(blob)
                fillRegex(blob)
            }
        }
        return byFileId.values.take(18)
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

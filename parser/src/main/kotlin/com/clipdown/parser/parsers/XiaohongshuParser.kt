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
 * 视频：`video.consumer.originVideoKey` → `sns-video-bd`（原片，长期有效）优先；
 *   转码流从 `stream.<codec>[]` 的 `backupUrls[0] ?: masterUrl` 补齐；
 *   **纯视频帖丢弃 imageList 封面帧**（与 IG 修复 10c 同源，避免"视频+封面图"被判图集）
 * 图集：由 `imageList[].fileId` 生成 `https://ci.xiaohongshu.com/<fileId>?imageView2/format/jpeg`
 *   （旧 sns-webpic 前缀签名会过期返回 403）；**按文件 ID 去重**
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
        // 媒体直链与解析请求使用同一套会话头：部分原图/视频需要 Referer + 登录 Cookie 才放行。
        // 这里在 warmUp 之后取，确保 acw_tc 等 WAF 种子也一并带给 CDN。
        val mediaHeaders = buildMap {
            put("Referer", "https://www.xiaohongshu.com/")
            headers["Cookie"]?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            headers["User-Agent"]?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
        }
        val direct = runCatching { ctx.http.get(url, headers) }.getOrNull()
        ctx.log(
            id,
            "直连结果：code=${direct?.code} len=${direct?.body?.length} " +
                "finalUrl=${direct?.finalUrl?.take(120)} imageList=${direct?.body?.contains("\"imageList\"")}"
        )

        var via = "okhttp"
        var respCode = direct?.code ?: 0
        var respBody: String? = direct?.body
        val directHasNote = respBody?.let {
            it.contains("\"imageList\"") || it.contains("\"noteDetailMap\"") ||
                (it.contains("\"noteData\"") && it.contains("\"noteId\""))
        } == true

        // 直连结果不可用（被 WAF 拦 / 空页 / 无笔记数据）时才走 WebView
        if (respBody.isNullOrBlank() || !directHasNote) {
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
                "state=${respBody?.contains("__INITIAL_STATE__")} imageList=${respBody?.contains("\"imageList\"")} " +
                "noteData=${respBody?.contains("\"noteData\"")} noteDetailMap=${respBody?.contains("\"noteDetailMap\"")}"
        )

        val html = respBody
            ?: throw ParseException("页面内容为空（可能触发了风控）", platform)
        if (via == "okhttp") {
            val finalUrl = direct?.finalUrl.orEmpty()
            if (finalUrl.contains("/404/", true) || finalUrl.contains("xhs_sec_server", true)) {
                throw ParseException(missingNoteMessage(), platform, retryable = false)
            }
            if (finalUrl.contains("/login", true) || finalUrl.contains("website-login", true)) {
                throw ParseException(loginMessage(), platform, retryable = false)
            }
        }
        if (html.contains("\"notFoundPage\"") || html.contains("\"code\":300031") || html.contains("error_code=300031")) {
            throw ParseException(missingNoteMessage(), platform, retryable = false)
        }
        if (respCode == 404) throw ParseException(missingNoteMessage(), platform, retryable = false)
        if (respCode >= 400) throw ParseException("页面返回 $respCode", platform)

        // 失效 / 登录墙守卫：小红书对"已删除、缺 xsec_token、失效"的笔记不返回 4xx，
        // 而是渲染「你访问的页面不见了」，或跳探索页；**未登录时还会 302 到 /login，
        // 而登录页的 <title> 与探索页完全相同**（都是"小红书 - 你的生活兴趣社区"，2026-10 实测）。
        // 这两类页面的 SSR 里塞的是推荐流，没有这道校验会把推荐流封面图当成图集返回。
        val pageTitle = HtmlUtil.meta(html, "og:title") ?: HtmlUtil.title(html)
        if (pageTitle != null && pageTitle.contains("你访问的页面不见了")) {
            throw ParseException(missingNoteMessage(), platform, retryable = false)
        }
        if (pageTitle != null && pageTitle.contains("你的生活兴趣社区")) {
            throw ParseException(loginMessage(), platform, retryable = false)
        }

        val state = HtmlUtil.inlineJson(html, "__INITIAL_STATE__")
        val blob = state ?: html
        val noteId = noteIdFromUrl(url)
        val scoped = state?.let { resolveNoteScope(it, noteId) }
        if (state != null) {
            ctx.log(id, "笔记作用域：${scoped?.source ?: "none"} noteId=${noteId ?: "-"}")
        }

        // 笔记详情页守卫：小红书对"已删除 / 缺 xsec_token / 失效"的笔记会跳到 /404 或探索页，
        // 页面里塞的是推荐流——没有这道校验会把推荐流的图当成图集返回（用户下到一堆无关图）。
        // 2026-11 线上主路径是 noteData.data.noteData；桌面端/旧版是 noteDetailMap；都不能落到 feed 推荐流。
        if (state != null && scoped == null) {
            throw ParseException(
                "链接不是笔记详情页：笔记可能已删除、链接失效或缺少 xsec_token（小红书已跳转到首页/探索页）",
                platform,
                retryable = false
            )
        }

        // 移动端与桌面端的 SSR 结构不同（2026-10 实测）：
        //   桌面：noteDetailMap → urlDefault/urlPre、displayTitle、nickname
        //   移动：imageList[].url（含 fileId）、title、user.nickName
        // 优先使用已解析出的笔记对象；只有在 __INITIAL_STATE__ 整体解析失败时才退回"首个 imageList 窗口"。
        val window = scoped?.text ?: noteWindow(blob)

        // 判断是否为纯视频笔记：
        // 1. type == "video"（如 discovery/item/... 移动端或桌面端标记）；
        // 2. 或者在 noteWindow 作用域内存在单独的 "video":{...} 对象，且该笔记不是 type=="normal" 的图文帖。
        // （小红书图文/实况图笔记 type 为 "normal"，其内仅在 imageList[].stream 中包含 LivePhoto 动图短视频，绝非纯视频帖）
        val noteType = HtmlUtil.jsonField(window, "type").firstOrNull()

        val items = extractImageListItems(blob, window, allowBlobFallback = scoped == null)
        val fallbackImages = if (items.isEmpty() && scoped == null) extractImages(blob, window) else emptyList()
        val videos = extractVideos(window).ifEmpty { if (scoped == null) extractVideos(blob) else emptyList() }
        val isVideoNote = noteType == "video" || (noteType == null && videos.isNotEmpty())

        // 判断是否为纯单视频笔记（有主视频且图片列表只有不超过1张封面图）：
        // 若同时存在主视频和多张图片，则属于图文+视频混合笔记，全面提取出全部图片与视频
        val isPureVideoNote = videos.isNotEmpty() && isVideoNote && items.size <= 1 && fallbackImages.size <= 1

        val title = listOfNotNull(
            HtmlUtil.jsonField(window, "displayTitle").firstOrNull()?.takeIf { it.isNotBlank() },
            HtmlUtil.jsonField(window, "title").firstOrNull()?.takeIf { it.isNotBlank() },
            HtmlUtil.meta(html, "og:title")?.substringBefore(" - 小红书")?.trim()?.takeIf { it.isNotBlank() },
            HtmlUtil.meta(html, "og:title")?.takeIf { it.isNotBlank() }
        ).firstOrNull()
        val author = authorFromWindow(window)
            ?: if (scoped == null) HtmlUtil.jsonField(blob, "nickname").firstOrNull()?.takeIf { it.isNotBlank() } else null
        val coverFileId = objectValue(window, "cover")?.text
            ?.let { HtmlUtil.jsonField(it, "fileId").firstOrNull() }
        val cover = items.firstOrNull()?.imageUrl
            ?: coverFileId?.let { imageUrlFromFileId(it) }
            ?: HtmlUtil.meta(html, "og:image")
            ?: fallbackImages.firstOrNull()
        val desc = HtmlUtil.jsonField(window, "desc").firstOrNull()?.takeIf { it.isNotBlank() }
            ?: HtmlUtil.meta(html, "og:description")
            ?: if (scoped == null) HtmlUtil.jsonField(blob, "desc").firstOrNull() else null

        val media = buildList {
            // 1. 视频资源（优先放置）
            if (videos.isNotEmpty()) {
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
                            headers = mediaHeaders,
                            fileNameHint = title
                        )
                    )
                }
            }

            // 2. 图片资源（若非纯单视频封面时，完整保留所有图片与 LivePhoto 实况动图）
            if (!isPureVideoNote) {
                if (items.isNotEmpty()) {
                    val totalImages = items.size
                    items.forEachIndexed { i, it ->
                        // 1. 无水印原图
                        add(
                            MediaItem(
                                id = "xhs-i-${it.imageUrl.hashCode().toString(16)}",
                                url = it.imageUrl,
                                kind = MediaKind.IMAGE,
                                quality = if (totalImages == 1) "原图" else "原图 ${i + 1}",
                                rank = 90 - i,
                                container = guessExt(it.imageUrl),
                                mimeType = "image/*",
                                headers = mediaHeaders,
                                fileNameHint = if (totalImages == 1) title else title?.let { t -> "${t}_${i + 1}" }
                            )
                        )
                        // 2. 实况动图短视频（若该图片为 LivePhoto 动态图片）
                        it.liveVideoUrl?.let { liveUrl ->
                            add(
                                MediaItem(
                                    id = "xhs-lv-${liveUrl.hashCode().toString(16)}",
                                    url = liveUrl,
                                    kind = MediaKind.VIDEO,
                                    quality = if (totalImages == 1) "实况动图" else "实况动图 ${i + 1}",
                                    rank = 85 - i,
                                    container = "mp4",
                                    mimeType = "video/mp4",
                                    headers = mediaHeaders,
                                    fileNameHint = if (totalImages == 1) title?.let { t -> "${t}_live" } else title?.let { t -> "${t}_${i + 1}_live" }
                                )
                            )
                        }
                    }
                } else if (fallbackImages.isNotEmpty()) {
                    fallbackImages.forEachIndexed { i, u ->
                        add(
                            MediaItem(
                                id = "xhs-i-${u.hashCode().toString(16)}",
                                url = u,
                                kind = MediaKind.IMAGE,
                                quality = "原图 ${i + 1}",
                                rank = 90 - i,
                                container = guessExt(u),
                                mimeType = "image/*",
                                headers = mediaHeaders,
                                fileNameHint = title?.let { "${it}_${i + 1}" }
                            )
                        )
                    }
                }
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
            warning = if (!isPureVideoNote && items.size <= 1 && fallbackImages.size <= 1 && HtmlUtil.meta(html, "og:description")?.contains("登录", true) == true)
                "仅获取到封面图，完整图集需要登录态" else null
        ).copy(description = desc, coverHeaders = mediaHeaders)
    }

    private data class VideoCandidate(val url: String, val height: Int)

    /**
     * 2026-11 线上视频结构：
     * - 原片：`video.consumer.originVideoKey` → `https://sns-video-bd.xhscdn.com/<key>`（长期有效，最优先）
     * - 转码：`video.media.stream.<codec>[]`，每个清晰度取 `backupUrls[0] ?: masterUrl`
     *
     * 旧实现全页取第一个 `"video":{...}`，在推荐流混排时会拿错视频；且 `backupUrls` 是数组，
     * `jsonField()` 解析不了，最终只剩带签名的 masterUrl（过期即 403）。现在只在笔记作用域内提取。
     */
    private fun extractVideos(note: String): List<String> {
        val video = objectValue(note, "video")?.text ?: return emptyList()
        val candidates = mutableListOf<VideoCandidate>()

        HtmlUtil.jsonField(video, "originVideoKey").forEach { key ->
            originVideoUrl(key)?.let { candidates.add(VideoCandidate(it, Int.MAX_VALUE)) }
        }

        val stream = objectValue(video, "stream")?.text
        if (stream != null) {
            Regex(""""(?:h264|h265|hevc|av1|avc)"\s*:\s*\[""").findAll(stream).forEach { match ->
                val open = stream.indexOf('[', match.range.first)
                val end = matchingContainer(stream, open, '[', ']') ?: return@forEach
                splitJsonObjectsInArray(stream.substring(open, end + 1)).forEach { obj ->
                    val height = HtmlUtil.jsonField(obj, "height").firstOrNull()?.toIntOrNull() ?: 0
                    val url = stringArrayField(obj, "backupUrls").firstOrNull()
                        ?: HtmlUtil.jsonField(obj, "masterUrl").firstOrNull()
                    if (!url.isNullOrBlank()) candidates.add(VideoCandidate(url, height))
                }
            }
        }

        if (candidates.isEmpty()) {
            val direct = stringArrayField(video, "backupUrls").firstOrNull()
                ?: HtmlUtil.jsonField(video, "masterUrl").firstOrNull()
            if (!direct.isNullOrBlank()) candidates.add(VideoCandidate(direct, 0))
            if (candidates.isEmpty()) {
                Regex("""(https?://sns-video[a-z0-9\-.]*\.xhscdn\.com/[^"'\s\\]+?\.mp4)""")
                    .findAll(video).forEach { candidates.add(VideoCandidate(it.groupValues[1], 0)) }
            }
        }

        return candidates
            .filter { it.url.startsWith("http") }
            .distinctBy { it.url }
            .sortedWith(compareByDescending<VideoCandidate> { it.height })
            .map { it.url }
    }

    private fun originVideoUrl(raw: String): String? {
        val key = raw.trim()
        if (key.isBlank()) return null
        if (key.startsWith("http://") || key.startsWith("https://")) return key
        return "https://sns-video-bd.xhscdn.com/${key.trimStart('/')}"
    }

    /** 取 JSON 字符串数组字段（如 `backupUrls`）；`HtmlUtil.jsonField` 对 `[` 开头的数组值不适用 */
    private fun stringArrayField(text: String, key: String): List<String> {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\[").find(text) ?: return emptyList()
        val open = text.indexOf('[', match.range.first)
        val end = matchingContainer(text, open, '[', ']') ?: return emptyList()
        return Regex(""""((?:[^"\\]|\\.)*)"""")
            .findAll(text.substring(open, end + 1))
            .map { HtmlUtil.unescapeJsonOf(it.groupValues[1]) }
            .filter { it.startsWith("http") }
            .toList()
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
        val scope = if (i >= 0) window.substring(i, (i + 2000).coerceAtMost(window.length)) else window
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

    data class XhsItem(
        val imageUrl: String,
        val liveVideoUrl: String? = null
    )

    /**
     * 从 imageList 数组切片中提取每个图片节点（含高画质图片直链及 LivePhoto 实况动图视频）
     */
    private fun extractImageListItems(blob: String, window: String, allowBlobFallback: Boolean = true): List<XhsItem> {
        val slice = imageListSlice(window)
            ?: (if (allowBlobFallback) imageListSlice(blob) else null)
            ?: return emptyList()
        val objs = splitJsonObjectsInArray(slice)
        if (objs.isEmpty()) return emptyList()

        val items = mutableListOf<XhsItem>()
        val seenFileIds = mutableSetOf<String>()

        for (obj in objs) {
            val fileId = HtmlUtil.jsonField(obj, "fileId").firstOrNull()?.trim().orEmpty()
            // 提取图片直链：优先级 urlDefault > url > urlPre > infoList 中的 URL
            val rawImgUrl = HtmlUtil.jsonField(obj, "urlDefault").firstOrNull()
                ?: HtmlUtil.jsonField(obj, "url").firstOrNull()
                ?: HtmlUtil.jsonField(obj, "urlPre").firstOrNull()
                ?: Regex("""https?://sns-webpic[a-z0-9\-.]*\.xhscdn\.com/[^"'\s\\]+""").find(obj)?.value

            val cleanImgUrl = rawImgUrl?.substringBefore("?")?.takeIf { it.startsWith("http") }
            val fid = fileId.ifBlank { cleanImgUrl?.let { fileIdFromImageUrl(it) } ?: "" }
            if (fid.isNotBlank() && !seenFileIds.add(fid)) {
                continue
            }
            val imageUrl = imageUrlFromFileId(fid) ?: cleanImgUrl ?: continue

            // 提取实况图动图视频（LivePhoto）
            val liveVideoUrl = if (obj.contains("\"livePhoto\":true") || obj.contains("\"stream\":{") || obj.contains("\"masterUrl\"")) {
                firstVideoUrlIn(obj)
            } else null

            items.add(XhsItem(imageUrl = imageUrl, liveVideoUrl = liveVideoUrl))
        }

        return items
    }

    private data class JsonHit(val text: String, val start: Int, val end: Int)
    private data class NoteScope(val text: String, val source: String)

    private fun noteIdFromUrl(url: String): String? =
        Regex("""/(?:explore|discovery/item|notes)/([0-9a-fA-F]{16,32})""")
            .find(url)?.groupValues?.getOrNull(1)

    /**
     * 笔记作用域优先级（与线上 2026-11 结构对齐）：
     * 1. `noteData.data.noteData`（当前主路径）
     * 2. `noteDetailMap[noteId].note` / 最后一个 note（桌面端与旧版）
     * 3. 顶层 `note`（部分移动端页面）
     * 4. 当前笔记 ID 匹配的 `noteCard`（最后兜底）
     *
     * 绝不能直接用页面第一个 `imageList`：SSR 常常先渲染 `feed.undertakeNote[].noteCard` 或推荐流，
     * 旧实现会把卡片/推荐流当成本帖数据。
     */
    private fun resolveNoteScope(state: String, noteId: String?): NoteScope? {
        var from = 0
        while (true) {
            val outer = objectValue(state, "noteData", from) ?: break
            val data = objectValue(outer.text, "data")
            val inner = data?.let { objectValue(it.text, "noteData") }
            if (inner != null && looksLikeNote(inner.text)) {
                return NoteScope(inner.text, "noteData.data.noteData")
            }
            if (inner == null && hasNoteIdentity(outer.text)) {
                return NoteScope(outer.text, "noteData")
            }
            from = outer.end + 1
        }

        val map = objectValue(state, "noteDetailMap")
        if (map != null) {
            if (!noteId.isNullOrBlank()) {
                val entry = objectValue(map.text, noteId)
                val note = entry?.let { objectValue(it.text, "note") }
                if (note != null && looksLikeNote(note.text)) {
                    return NoteScope(note.text, "noteDetailMap[$noteId]")
                }
            }
            val notes = objectValues(map.text, "note").filter { looksLikeNote(it.text) }
            notes.lastOrNull()?.let { return NoteScope(it.text, "noteDetailMap.last") }
        }

        val topNotes = objectValues(state, "note").filter { looksLikeNote(it.text) }
        topNotes.lastOrNull()?.let { return NoteScope(it.text, "note") }

        val cards = objectValues(state, "noteCard").filter { looksLikeNote(it.text) }
        cards.firstOrNull {
            noteId.isNullOrBlank() ||
                HtmlUtil.jsonField(it.text, "noteId").firstOrNull()?.equals(noteId, true) == true
        }?.let { return NoteScope(it.text, "noteCard") }

        return null
    }

    private fun looksLikeNote(text: String): Boolean =
        text.contains("\"noteId\"") || text.contains("\"imageList\"") || text.contains("\"video\"")

    private fun hasNoteIdentity(text: String): Boolean =
        text.contains("\"noteId\"") || text.contains("\"imageList\"")

    private fun objectValue(text: String, key: String, from: Int = 0): JsonHit? {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\\{").find(text, from) ?: return null
        val open = text.indexOf('{', match.range.first)
        if (open < 0) return null
        val end = matchingContainer(text, open, '{', '}') ?: return null
        return JsonHit(text.substring(open, end + 1), open, end)
    }

    private fun objectValues(text: String, key: String): List<JsonHit> {
        val out = mutableListOf<JsonHit>()
        var from = 0
        while (true) {
            val hit = objectValue(text, key, from) ?: break
            out.add(hit)
            from = hit.end + 1
        }
        return out
    }

    private fun matchingContainer(text: String, open: Int, openChar: Char, closeChar: Char): Int? {
        if (open !in text.indices || text[open] != openChar) return null
        var depth = 0
        var inStr = false
        var escaped = false
        for (p in open until text.length) {
            val c = text[p]
            when {
                escaped -> escaped = false
                c == '\\' && inStr -> escaped = true
                c == '"' -> inStr = !inStr
                !inStr && c == openChar -> depth++
                !inStr && c == closeChar -> {
                    depth--
                    if (depth == 0) return p
                }
            }
        }
        return null
    }

    /**
     * 2026-11 起旧 `sns-webpic` 前缀签名会过期（403），fileId 对应的 ci 图片服务仍长期可用。
     * fileId 形如 `notes_pre_post/xxxx` 或裸 `xxxx`，直接拼在域名后，不要再加前缀。
     */
    private fun imageUrlFromFileId(rawFileId: String): String? {
        var id = rawFileId.trim()
        if (id.isBlank()) return null
        if (id.startsWith("http://") || id.startsWith("https://")) {
            id = fileIdFromImageUrl(id)
        }
        id = id.replace("\\/", "/")
            .substringBefore('?')
            .substringBefore('!')
            .trim()
            .trimStart('/')
        if (id.isBlank()) return null
        return "https://ci.xiaohongshu.com/$id?imageView2/format/jpeg"
    }

    private fun fileIdFromImageUrl(url: String): String {
        val path = url.replace("\\/", "/").substringBefore('?').substringBefore('!')
        val marker = path.indexOf("/notes_pre_post/")
        if (marker >= 0) return path.substring(marker + 1)
        return path.substringAfterLast('/')
    }

    private fun firstVideoUrlIn(text: String): String? {
        HtmlUtil.jsonField(text, "originVideoKey").firstOrNull()?.let { return originVideoUrl(it) }
        HtmlUtil.jsonField(text, "masterUrl").firstOrNull()?.takeIf { it.startsWith("http") }?.let { return it }
        stringArrayField(text, "backupUrls").firstOrNull()?.let { return it }
        Regex("""https?://sns-video[a-z0-9\-.]*\.xhscdn\.com/[^"'\s\\]+?\.mp4""")
            .find(text)?.value?.let { return it }
        return null
    }

    private fun missingNoteMessage(): String =
        "笔记不存在或已失效（小红书返回了失效页/安全校验页）；" +
            "请在小红书 App 重新复制分享链接（需带 xsec_token），或补充 Cookie 后重试"

    private fun loginMessage(): String =
        "小红书要求登录态，或该链接已失效（页面被跳到了首页/登录页）。" +
            "请在「设置」中补充小红书 Cookie 后重试，或重新复制带 xsec_token 的分享链接"

    /**
     * 将 JSON 数组字符串（形如 `[{...}, {...}]`）切分成内部每个顶层 JSON 对象的字符串列表。
     */
    private fun splitJsonObjectsInArray(arrayJson: String): List<String> {
        val openIdx = arrayJson.indexOf('[')
        if (openIdx < 0) return emptyList()
        val objs = mutableListOf<String>()
        var depth = 0
        var inStr = false
        var escaped = false
        var objStart = -1

        for (p in (openIdx + 1) until arrayJson.length) {
            val c = arrayJson[p]
            when {
                escaped -> escaped = false
                c == '\\' && inStr -> escaped = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> {
                    if (depth == 0) objStart = p
                    depth++
                }
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        objs.add(arrayJson.substring(objStart, p + 1))
                        objStart = -1
                    }
                }
                !inStr && depth == 0 && c == ']' -> break
            }
        }
        return objs
    }

}

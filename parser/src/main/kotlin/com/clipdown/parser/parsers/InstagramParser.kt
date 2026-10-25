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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Instagram 解析器。
 *
 * 多级降级策略：
 * 1. 登录态直连接口（用户在设置里提供了 Cookie）：
 *    a. 移动端 REST 接口：`/api/v1/media/{media_id}/info/`（直接给结构化 items 原生数据，无需 GraphQL/LSD）；
 *    b. 网页端 GraphQL 接口：`/api/graphql` 与 `/graphql/query`，自动获取 LSD 与 CSRF 令牌，
 *       兼容 xdt_shortcode_media / xig_polaris_media / shortcode_media 多代结构；
 * 2. 免登录 embed 页：`/p/{code}/embed/captioned/`，提取视频直链与单张图片；
 * 3. WebView 真实浏览器渲染（带 Cookie 注入，支持 /reel/ 与 /p/ 双路径探测及 DOM <video> 标签抓取）；
 * 4. oEmbed 公开接口：缩略图兜底并给出画质提示。
 */
class InstagramParser : PlatformParser {

    override val platform: Platform = Platform.INSTAGRAM
    override val id: String = "ig-local-v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val shortcodeRegex =
        Regex("""(?:instagram\.com|instagr\.am)/(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,})""")

    override fun canHandle(url: String): Boolean = shortcodeRegex.containsMatchIn(url)

    // 风控保护（修复 11）：连续失败冷却；状态与主页链路共用 IgRiskGuard，避免两条链路各自计数被绕过。
    // 缺登录态或认证失效的失败不计入，避免用户"试两次"就锁进 10 分钟冷却。
    override fun parse(url: String, ctx: ParseContext): ParseResult {
        val hasCookie = !ctx.cookieProvider(platform).isNullOrBlank()
        return IgRiskGuard.guard(ctx, id, countsFailure = hasCookie) { parseChannels(url, ctx) }
    }

    private fun parseChannels(url: String, ctx: ParseContext): ParseResult {
        val code = shortcodeRegex.find(url)?.groupValues?.getOrNull(1)
            ?: throw ParseException("无法从链接中提取作品 ID", platform, retryable = false)

        val cookie = ctx.cookieProvider(platform)
        val headers = ctx.headersFor(platform, desktop = false) +
            mapOf("Referer" to "https://www.instagram.com/", "Accept" to "*/*")
        // 通道选择取证：登录态缺失时 private 被跳过，图集子图数据（carousel）只有登录态或渲染页能给全
        ctx.log(id, "通道选择 cookie=${!cookie.isNullOrBlank()} code=$code")

        // 1) 登录态直连（移动端 REST info 接口 + 网页端 GraphQL 双通道）
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
            val parsed = parseEmbed(embedHtml, url, code, ctx)
            if (parsed.media.any { it.kind == MediaKind.VIDEO }) return parsed
            if (!parsed.isEmpty) {
                // embed 只有图片时，先别急着返回——WebView 渲染页可能拿到视频（见 2.5）
                embedFallback = parsed
            }
        }

        // 2.5) WebView 渲染抓取（App 层注入；带登录态 Cookie 渲染帖子页，HTML 内含视频数据）。
        // 优先沿用用户链接的原路径，若渲染未拿到媒体则自动切换路径（/reel/ 与 /p/ 分别探测）
        val primary = if (url.contains("/reel/", ignoreCase = true)) "reel" else "p"
        val secondary = if (primary == "reel") "p" else "reel"

        var rendered = runCatching { ctx.webFetcher?.invoke("https://www.instagram.com/$primary/$code/") }.getOrNull()
        if (!rendered.isNullOrBlank()) {
            val fromPage = extractFromPageHtml(rendered, url, code)
            if (!fromPage.isEmpty) return fromPage
        }
        rendered = runCatching { ctx.webFetcher?.invoke("https://www.instagram.com/$secondary/$code/") }.getOrNull()
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

        if (!cookie.isNullOrBlank()) {
            throw ParseException("Instagram 解析失败，已配置 Cookie 但未能获取媒体数据，建议在设置中更新 Cookie 或开启远端解析服务", platform)
        }
        throw ParseException("Instagram 解析失败，建议补充 Cookie 或开启远端解析服务", platform)
    }

    /**
     * 将 Instagram shortcode 转换为 64 进制的 numeric media ID (pk)
     */
    internal fun shortcodeToMediaId(code: String): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val clean = if (code.length > 28) code.dropLast(28) else code
        var pk = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(64)
        for (ch in clean) {
            val idx = chars.indexOf(ch)
            if (idx >= 0) {
                pk = pk.multiply(base).add(java.math.BigInteger.valueOf(idx.toLong()))
            }
        }
        return pk.toString()
    }

    private fun parsePrivateApi(code: String, headers: Map<String, String>, ctx: ParseContext): ParseResult {
        val cookie = headers["Cookie"] ?: ""
        val mediaId = shortcodeToMediaId(code)

        // 1. 优先尝试移动端/网页端原生 REST /api/v1/media/{media_id}/info/
        // 该接口为官方 App 原生端点，直接返回 items[0] 结构，无需 LSD / doc_id，稳定性极高
        if (mediaId.isNotBlank() && mediaId != "0") {
            for (endpoint in listOf(
                "https://i.instagram.com/api/v1/media/$mediaId/info/",
                "https://www.instagram.com/api/v1/media/$mediaId/info/"
            )) {
                val apiHeaders = mapOf(
                    "User-Agent" to "Instagram 315.0.0.38.109 Android (33/13; 420dpi; 1080x2400; Google/google; Pixel 6; oriole; cheetah; en_US; 560243200)",
                    "X-IG-App-ID" to "124024574287414",
                    "Cookie" to cookie,
                    "Accept" to "*/*"
                )
                val resp = runCatching { ctx.http.get(endpoint, apiHeaders) }.getOrNull()
                if (resp != null && resp.isSuccessful && !resp.body.isNullOrBlank()) {
                    val root = runCatching { json.parseToJsonElement(resp.body).jsonObject }.getOrNull()
                    val firstItem = root?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
                    if (firstItem != null) {
                        val parsed = runCatching { parseMediaNode(firstItem, code, "$id-v1-info", ctx) }.getOrNull()
                        if (parsed != null && !parsed.isEmpty) {
                            ctx.log(id, "REST info 成功: media=${parsed.media.size}")
                            return parsed
                        }
                    }
                } else if (resp != null) {
                    ctx.log(id, "REST info $endpoint code=${resp.code}")
                }
            }
        }

        // 2. 网页端 GraphQL 通道
        val pageHeaders = mapOf(
            "User-Agent" to ctx.ua(desktop = true),
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
            "Referer" to "https://www.instagram.com/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Cookie" to cookie
        )
        val page = runCatching { ctx.http.get("https://www.instagram.com/p/$code/", pageHeaders) }.getOrNull()
            ?: throw ParseException("无法获取帖子页面", platform)
        val html = page.body ?: throw ParseException("帖子页面返回为空", platform)
        val lsd = Regex(""""LSD",\[\],\{"token":"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex(""""lsd":"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""\["LSD",\[\],\{"token":"([^"]+)"\]""").find(html)?.groupValues?.getOrNull(1)
        if (lsd.isNullOrBlank()) {
            ctx.log(id, "LSD 提取失败：code=${page.code} len=${html.length}")
            throw ParseException("页面缺少 LSD 令牌", platform)
        }

        // 提取并自动补齐 CSRF 令牌（兼容用户仅复制 sessionid 的情况）
        val extractedCsrf = Regex("csrftoken=([^;]+)").find(cookie)?.groupValues?.getOrNull(1)
            ?: Regex(""""(?:csrf_token|csrftoken)":"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""csrftoken=([^;]+)""").find(page.header("set-cookie") ?: "")?.groupValues?.getOrNull(1)
            ?: ""
        val effectiveCookie = if (cookie.contains("csrftoken=")) cookie
        else if (extractedCsrf.isNotBlank()) "$cookie; csrftoken=$extractedCsrf"
        else cookie

        val variables = """{"shortcode":"$code","fetch_tagged_user_count":null,"hoisted_comment_id":null,"hoisted_reply_id":null}"""
        val form = "lsd=${urlEncode(lsd)}&fb_api_caller_class=RelayModern&fb_api_req_friendly_name=PolarisPostActionLoadPostQueryQuery&variables=${urlEncode(variables)}&doc_id=8845758582119845&server_timestamps=true"
        val apiHeaders = mapOf(
            "User-Agent" to ctx.ua(desktop = true),
            "X-IG-App-ID" to "936619743392459",
            "X-CSRF-Token" to extractedCsrf,
            "X-CSRFToken" to extractedCsrf,
            "X-FB-LSD" to lsd,
            "X-ASBD-ID" to "129477",
            "X-FB-Friendly-Name" to "PolarisPostActionLoadPostQueryQuery",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://www.instagram.com/p/$code/",
            "Cookie" to effectiveCookie,
            "Accept" to "*/*"
        )

        var body: String? = null
        for (endpoint in listOf("https://www.instagram.com/api/graphql", "https://www.instagram.com/graphql/query")) {
            val resp = runCatching { ctx.http.postForm(endpoint, form, apiHeaders) }.getOrNull()
            if (resp != null && resp.isSuccessful && !resp.body.isNullOrBlank() && resp.body.trimStart().startsWith("{")) {
                body = resp.body
                break
            } else if (resp != null) {
                ctx.log(id, "GraphQL $endpoint code=${resp.code} head=${resp.body?.take(100)?.replace(Regex("\\s+"), " ")}")
            }
        }
        if (body.isNullOrBlank()) {
            throw ParseException("GraphQL 请求失败或未返回有效 JSON 数据", platform)
        }

        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse {
            ctx.log(id, "GraphQL 非 JSON：${body.take(140).replace(Regex("\\s+"), " ")}")
            throw ParseException("GraphQL 返回异常格式", platform)
        }
        val data = root["data"]?.jsonObject
        val node = data?.get("xdt_shortcode_media")?.jsonObject
            ?: data?.get("shortcode_media")?.jsonObject
            ?: data?.get("xig_polaris_media")?.jsonObject?.let {
                it["if_not_gated_logged_out"]?.jsonObject ?: it["if_not_gated"]?.jsonObject ?: it
            }
            ?: root["items"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: run {
                // 如果外层包装键名改变，在 root 中递归查找包含媒体字段的候选节点
                var candidate: JsonObject? = null
                fun findCandidate(el: JsonElement) {
                    if (candidate != null) return
                    when (el) {
                        is JsonObject -> {
                            if (el.containsKey("video_versions") || el.containsKey("image_versions2") || el.containsKey("carousel_media")) {
                                candidate = el
                            } else {
                                el.values.forEach(::findCandidate)
                            }
                        }
                        is JsonArray -> el.forEach(::findCandidate)
                        else -> Unit
                    }
                }
                findCandidate(root)
                candidate
            }
            ?: run {
                ctx.log(id, "GraphQL 无媒体数据：${body.take(160).replace(Regex("\\s+"), " ")}")
                throw ParseException("GraphQL 无媒体数据", platform)
            }

        return parseMediaNode(node, code, "$id-private", ctx)
    }

    private fun parseMediaNode(
        node: JsonObject,
        code: String,
        resolverId: String,
        ctx: ParseContext
    ): ParseResult {
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
        val sidecar = (node["edge_sidecar_to_children"] ?: node["edge_media_to_sidecar_to_children"])?.jsonObject
        (sidecar?.get("edges") as? JsonArray)?.forEachIndexed { i, el ->
            el.jsonObject["node"]?.jsonObject?.let { addFrom(it, "ig-g-s$i", 70 - i) }
        }
        // 图集/混合相册（图+视频）子项在 carousel_media 数组，每个子项各自带 video_versions 或 image_versions2
        (node["carousel_media"] as? JsonArray)?.forEachIndexed { i, el ->
            runCatching { el.jsonObject }.getOrNull()?.let { addFrom(it, "ig-g-c$i", 70 - i) }
        }
        if (media.isEmpty()) {
            // 兜底递归寻找媒体子节点
            var found = 0
            fun scan(o: JsonElement) {
                when (o) {
                    is JsonObject ->
                        if (o.containsKey("video_versions") || o.containsKey("image_versions2")) {
                            addFrom(o, "ig-g-x${found}", 60 - found)
                            found++
                        } else {
                            o.values.forEach(::scan)
                        }
                    is JsonArray -> o.forEach(::scan)
                    else -> Unit
                }
            }
            scan(node)
        }

        if (media.isEmpty()) throw ParseException("媒体节点未提取到媒体内容", platform)

        ctx.log(id, "$resolverId 成功 media=${media.size} " + media.joinToString("|") { "${it.kind.name[0]}:${it.url.take(70)}" })

        return ParserDsl.result(
            platform = platform,
            resolverId = resolverId,
            sourceUrl = "https://www.instagram.com/p/$code/",
            media = media.sanitized(),
            title = node["caption"]?.jsonObject?.str("text")?.take(60)
                ?: (node["edge_media_to_caption"]?.jsonObject?.get("edges") as? JsonArray)
                    ?.firstOrNull()?.jsonObject?.get("node")?.jsonObject?.str("text")?.take(60)
                ?: node.str("title")?.take(60),
            author = node["owner"]?.jsonObject?.str("username")
                ?: node["user"]?.jsonObject?.str("username"),
            cover = (node["image_versions2"]?.jsonObject?.get("candidates") as? JsonArray)
                ?.firstOrNull()?.jsonObject?.str("url"),
            source = ParseSource.LOCAL
        )
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * 出口强制清洗（修复 9 的最后一道防线）：解析各通道产出的媒体 URL 统一还原页面转义
     * （`\/` 形态无论几层残留都清除——页面转义形态随版本变化，历史上有三条提取路径各自漏网）
     * 并按 URL 去重（同图多形态提取产生的重复项）。
     */
    private fun List<MediaItem>.sanitized(): List<MediaItem> =
        map { it.copy(url = it.url.replace("\\/", "/")) }
            .distinctBy { it.url }

    /**
     * 截取正帖数据范围：`xdt_api__v1__media__shortcode__web_info.items[0]` 或 `xig_polaris_media`。
     * 该容器只含用户请求的这条帖子；页面其余位置的媒体数据全部来自推荐流，必须排除。
     * 找不到容器时回退整页（保持旧行为兼容）。
     */
    private fun ownPostScope(html: String, code: String): String {
        val key = "\"xdt_api__v1__media__shortcode__web_info\""
        val kPos = html.indexOf(key)
        if (kPos >= 0) {
            val itemsKey = html.indexOf("\"items\":[", kPos)
            if (itemsKey >= 0) {
                val arr = balancedSlice(html, itemsKey + "\"items\":".length, '[', ']')
                if (arr != null) {
                    val objStart = arr.indexOf('{')
                    if (objStart >= 0) {
                        balancedSlice(arr, objStart, '{', '}')?.let { return it }
                    }
                }
            }
        }
        val polarisKey = "\"xig_polaris_media\""
        val pPos = html.indexOf(polarisKey)
        if (pPos >= 0) {
            balancedSlice(html, pPos, '{', '}')?.let { return it }
        }
        return html
    }

    /**
     * items[0] 内部嵌推荐块时，按 code/shortcode 切段只保留正帖所在段——嵌块的陌生视频/封面帧不再混入媒体列表。
     * 找不到正帖 code 时保持原 scope（异常页面兼容）。
     */
    private fun ownCodeSegment(scope: String, code: String): String {
        if (code.isBlank()) return scope
        val codeMatches = Regex(""""(?:code|shortcode)":"([^"]+)"""").findAll(scope).toList()
        val own = codeMatches.firstOrNull { it.groupValues[1] == code } ?: return scope
        val end = codeMatches.firstOrNull { it.range.first > own.range.first }?.range?.first ?: scope.length
        return scope.substring(own.range.first, end)
    }

    /** 括号配对截取：从 start 处的 open 起到与之配对的 close 止（含字符串状态，防数据内括号干扰） */
    private fun balancedSlice(s: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var inStr = false
        var esc = false
        val end = minOf(s.length, start + 800_000)
        for (i in start until end) {
            val c = s[i]
            if (esc) {
                esc = false
                continue
            }
            if (inStr) {
                when (c) {
                    '\\' -> esc = true
                    '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> inStr = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** 渲染后的帖子页 HTML 提取（WebView 通道）：新版 xdt_api / xig_polaris 结构 + DOM 标签兜底 */
    private fun extractFromPageHtml(html: String, sourceUrl: String, code: String): ParseResult {
        // 关键隔离：页面还含有"更多帖子"推荐流的数据（image_versions2 可达 35 个），
        // 正帖数据在 xdt_api / xig_polaris_media / items[0]，限定在此范围内提取。
        val scope = ownPostScope(html, code)
        val segment = ownCodeSegment(scope, code)
        val igHeaders = mapOf("Referer" to "https://www.instagram.com/")
        val media = mutableListOf<MediaItem>()

        val videos = linkedSetOf<String>()
        // 新版结构：video_versions:[{width,height,url}]
        Regex(""""video_versions":\[(.*?)\]""").findAll(segment).forEach { block ->
            Regex(""""url":"([^"]+)"""").findAll(block.groupValues[1]).forEach { u ->
                videos.add(HtmlUtil.unescapeJsonOf(u.groupValues[1]))
            }
        }
        // 旧版键名兜底
        HtmlUtil.jsonField(segment, "playable_url_quality_hd").firstOrNull()?.let { videos.add(it) }
        HtmlUtil.jsonField(segment, "video_url").forEach { videos.add(it) }
        HtmlUtil.jsonField(segment, "playable_url").forEach { videos.add(it) }
        // 从整页 meta 标签取（meta 在 <head> 中，不能仅在 scope 查）
        Regex("""<meta property="og:video" content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)?.let {
            videos.add(HtmlUtil.unescapeHtmlOf(it.groupValues[1]))
        }
        // DOM <video> / <source> 标签兜底（WebView 中若播放器已渲染出 DOM）
        Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val u = HtmlUtil.unescapeHtmlOf(m.groupValues[1])
            if (u.startsWith("http") && !u.startsWith("blob:")) videos.add(u)
        }
        Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            val u = HtmlUtil.unescapeHtmlOf(m.groupValues[1])
            if (u.startsWith("http") && !u.startsWith("blob:")) videos.add(u)
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
        // 新版结构：image_versions2.candidates:[{width,height,url}]——图集每个子项一块，
        // 每块取宽度最大的一个（candidates 是同一张图的多尺寸候选），并跳过视频首帧缩略图
        Regex(""""image_versions2":\{"candidates":\[(.*?)\]\}""").findAll(segment).forEach { block ->
            val pairs = Regex(""""width":(\d+),"height":\d+,"url":"([^"]+)"""")
                .findAll(block.groupValues[1])
                .map { it.groupValues[1].toInt() to HtmlUtil.unescapeJsonOf(it.groupValues[2]) }
                .filter { !it.second.contains("video_first_frame", ignoreCase = true) }
                .toList()
            val best = pairs.maxByOrNull { it.first }?.second
                ?: Regex(""""url":"([^"]+)"""").findAll(block.groupValues[1])
                    .map { HtmlUtil.unescapeJsonOf(it.groupValues[1]) }
                    .firstOrNull { !it.contains("video_first_frame", ignoreCase = true) }
            best?.let { images.add(it) }
        }
        HtmlUtil.jsonField(segment, "display_url").forEach { images.add(it) }
        // 从整页 meta 标签取
        Regex("""<meta property="og:image" content="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)?.let {
            images.add(HtmlUtil.unescapeHtmlOf(it.groupValues[1]))
        }

        // WebView 渲染页对图集帖可能只给首图（子图走懒加载不进 HTML）——但图集子图已在 DOM 渲染为 <img>。兜底提取
        if (images.size <= 1 && videos.isEmpty()) {
            Regex("""<img[^>]+src="(https://scontent[^"]+)"[^>]*>""").findAll(html).forEach { m ->
                val u = HtmlUtil.unescapeHtmlOf(m.groupValues[1])
                when {
                    u.contains("t51.2885-19") || u.contains("s150x150") -> Unit
                    images.any { it.substringBefore('?') == u.substringBefore('?') } -> Unit
                    else -> images.add(u)
                }
            }
        }
        images.filter { it.startsWith("http") }.take(12).forEachIndexed { i, u ->
            media += MediaItem(
                id = "ig-w-i-$i",
                url = u,
                kind = MediaKind.IMAGE,
                quality = if (images.size > 1) "图 ${i + 1}" else "原图",
                rank = 80 - i,
                container = "jpg",
                headers = igHeaders
            )
        }

        // 链接带 img_index=N 时（用户在图集里复制了第 N 张），把该图排到首位作为默认选中
        val imgIndex = Regex("img_index=(\\d+)").find(sourceUrl)?.groupValues?.get(1)?.toInt()
        if (imgIndex != null && imgIndex in 1..images.size) {
            val list = images.toList()
            val ordered = listOf(imgIndex - 1) + list.indices.filter { it != imgIndex - 1 }
            media.removeAll { it.id.startsWith("ig-w-i-") }
            ordered.forEachIndexed { i, idx ->
                val u = list[idx]
                media += MediaItem(
                    id = "ig-w-i-$i",
                    url = u,
                    kind = MediaKind.IMAGE,
                    quality = if (i == 0) "你选的第 $imgIndex 张" else "图 ${idx + 1}",
                    rank = 90 - i,
                    container = "jpg",
                    headers = igHeaders
                )
            }
        }

        if (media.isEmpty()) return ParserDsl.result(platform, "$id-page", sourceUrl, emptyList())

        return ParserDsl.result(
            platform = platform,
            resolverId = "$id-page",
            sourceUrl = sourceUrl,
            media = media.sanitized(),
            title = HtmlUtil.meta(html, "og:title")?.take(60),
            author = HtmlUtil.jsonField(html, "username").firstOrNull()
                ?: Regex("""instagram\.com/([A-Za-z0-9_.]+)/""").find(html)?.groupValues?.getOrNull(1),
            cover = images.firstOrNull(),
            source = ParseSource.LOCAL
        )
    }

    private fun parseEmbed(html: String, sourceUrl: String, code: String, ctx: ParseContext): ParseResult {
        val media = mutableListOf<MediaItem>()
        val igHeaders = mapOf("Referer" to "https://www.instagram.com/")

        val segment = ownCodeSegment(html, code)

        HtmlUtil.jsonField(segment, "video_url").firstOrNull()?.let { u ->
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
        HtmlUtil.jsonField(segment, "display_url").forEach { images += it }
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

        ctx.log(id, "embed 成功 media=${media.size} " + media.joinToString("|") { "${it.kind.name[0]}:${it.url.take(70)}" })

        // embed 通道：含视频时图片一律为封面帧/重复候选，单视频帖不该被判为图集——只保留视频
        val finalMedia = if (media.any { it.kind == MediaKind.VIDEO }) media.filter { it.kind == MediaKind.VIDEO } else media

        val title = HtmlUtil.meta(html, "og:title") ?: HtmlUtil.title(html)
        val author = HtmlUtil.jsonField(html, "username").firstOrNull()
            ?: Regex("""instagram\.com/([A-Za-z0-9_.]+)/""").find(html)?.groupValues?.getOrNull(1)

        return ParserDsl.result(
            platform = platform,
            resolverId = "$id-embed",
            sourceUrl = sourceUrl,
            media = finalMedia.sanitized(),
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

package com.clipdown.parser.parsers

import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.Platform
import com.clipdown.parser.model.PostKind
import com.clipdown.parser.model.ProfilePost
import com.clipdown.parser.model.ProfileResult
import com.clipdown.parser.model.ProfileStats
import com.clipdown.parser.spi.ParseContext
import com.clipdown.parser.spi.ProfileParser

/**
 * 小红书博主主页解析器。
 *
 * 数据来源（2026-10 实测取证，`https://www.xiaohongshu.com/user/profile/<userId>`）：
 * - **桌面 UA + 登录态 Cookie** → 返回 SSR 完整主页：`userPageData`（昵称/头像/简介/关注粉丝获赞）
 *   + `notes`（二维数组，元素为 `{id, noteCard}`，含 `displayTitle`/`cover`/`time`/`xsecToken`/`type`）
 * - 移动端 UA 只返回客户端渲染外壳（无笔记数据）；无 Cookie 会被 302 到 /login
 * - 因此顺序为：**桌面 UA 直连 → 移动端 UA 直连 → WebView 渲染**（后者用于 WAF 指纹挑战等场景）
 *
 * 每篇笔记的 URL 会带上 SSR 里的 `xsecToken`——**这是点开/下载单篇所必需的**，
 * 裸 `/explore/<id>` 会被判为无效链接。
 */
class XiaohongshuProfileParser : ProfileParser {

    override val platform: Platform = Platform.XIAOHONGSHU
    override val id: String = "xhs-profile-v1"

    /**
     * 单次解析的笔记上限。翻页时按页数放大——若固定上限，第二轮抓到的
     * 新数据会被前几轮的已知项挤掉（实测 92 篇后再翻页恒为"没有更多了"）。
     */
    private var maxPosts = 120

    override fun parseProfile(
        url: String,
        handle: String,
        ctx: ParseContext,
        pages: Int
    ): ProfileResult {
        maxPosts = (pages * 60).coerceIn(120, 360)
        val attempts = mutableListOf<Pair<String, String?>>()
        // 1) 桌面 UA（主页 SSR 的必需条件）
        attempts += "desktop" to fetch(url, ctx, desktop = true)
        // 2) 移动端 UA 兜底
        if (attempts.none { !it.second.isNullOrBlank() }) {
            attempts += "mobile" to fetch(url, ctx, desktop = false)
        }
        // 3) WebView 渲染兜底（WAF / 结构变化）
        if (attempts.none { !it.second.isNullOrBlank() }) {
            attempts += "webview" to runCatching { ctx.webFetcher?.invoke(url) }.getOrNull()
        }

        val (via, page) = attempts.firstOrNull { !it.second.isNullOrBlank() }
            ?: throw ParseException("主页内容为空（可能触发了风控，或未配置小红书 Cookie）", platform)

        val html = page!!

        // 登录墙/失效守卫：与笔记链路一致
        val pageTitle = HtmlUtil.meta(html, "og:title") ?: HtmlUtil.title(html)
        if (pageTitle != null && pageTitle.contains("你的生活兴趣社区")) {
            throw ParseException(
                "小红书要求登录态（主页被跳到了首页/登录页）。请在「设置」中补充小红书 Cookie 后重试",
                platform,
                retryable = false
            )
        }

        val state = HtmlUtil.inlineJson(html, "__INITIAL_STATE__")
            ?: throw ParseException("未能从主页页面中提取到数据（页面结构可能已更新）", platform)

        val profileWindow = windowAround(state, "\"userPageData\"", before = 200, after = 4000) ?: state
        var posts = extractNoteCards(state)
        ctx.log(
            id,
            "主页解析：via=$via handle=$handle 首屏笔记=${posts.size} " +
                "state=${state.length} userPageData=${state.contains("userPageData")}"
        )

        // 加载更多：SSR 只给首屏一页，分页接口需要 x-s 签名无法重放，
        // 因此让页面自己的 JS 在 WebView 里滚动触发无限滚动，并回收它拿到的接口响应。
        // 必须桌面 UA：移动端 Web 的卡片 DOM（reds-note-card）里没有笔记 ID 与链接，提取不到。
        if (pages > 1) {
            // 一页做 3 轮「回顶→滚底」扫动（单方向滚到底会钉住，哨兵不再触发下一页）
            val raw = runCatching { ctx.webFetcherScroll?.invoke(url, (pages - 1) * 3, true) }.getOrNull()
            if (!raw.isNullOrBlank()) {
                val sep = raw.indexOf(PAGES_SEP)
                val dom = if (sep >= 0) raw.substring(0, sep) else raw
                val fromApi = if (sep >= 0) extractFromApiPages(raw.substring(sep + PAGES_SEP.length), ctx) else emptyList()
                val known = posts.map { it.id }.toSet()
                val added = fromApi.filter { it.id !in known }.ifEmpty {
                    // 接口 JSON 拿不到时退回渲染 DOM（桌面版卡片是 <a href="/explore/<id>">）
                    extractFromDom(dom).filter { it.id !in known }
                }
                ctx.log(id, "加载更多：接口=${fromApi.size} 新增=${added.size}")
                posts = posts + added
            } else {
                ctx.log(id, "加载更多：滚动抓取不可用（笔记本=${posts.size}）")
            }
        }

        if (posts.isEmpty()) {
            throw ParseException(
                "未能从主页中提取到笔记列表（可能需要登录态，或该账号没有公开笔记）",
                platform
            )
        }

        return ProfileResult(
            platform = platform,
            resolverId = id,
            sourceUrl = url,
            userId = handle,
            nickname = HtmlUtil.jsonField(profileWindow, "nickname").firstOrNull()?.takeIf { it.isNotBlank() },
            avatar = HtmlUtil.jsonField(profileWindow, "images").firstOrNull()
                ?: HtmlUtil.jsonField(profileWindow, "imageb").firstOrNull(),
            bio = HtmlUtil.jsonField(profileWindow, "desc").firstOrNull()
                ?.takeIf { it.isNotBlank() && it != "还没有简介" },
            stats = extractStats(profileWindow),
            posts = posts,
            hasMore = posts.size >= PAGE_SIZE,
            warning = if (posts.size >= PAGE_SIZE) "已加载 ${posts.size} 篇，可点下方「加载更多」继续" else null
        )
    }

    /**
     * 从**渲染后的 DOM** 提取笔记卡片（翻页补数据用）。
     *
     * 渲染后的卡片是 `<a href="/explore/<id>?xsec_token=...">`，封面在其中的 `<img>`，
     * 标题是卡片里的文本节点；`xsec_token` 直接从 href 里取（点开/下载单篇必需）。
     */
    private fun extractFromDom(html: String): List<ProfilePost> {
        val out = LinkedHashMap<String, ProfilePost>()
        val card = Regex(
            """<a\s[^>]*href\s*=\s*["'](?:https?://[^"']*?)?/(?:explore|discovery/item)/([0-9a-zA-Z]+)([^"']*)["'][^>]*>([\s\S]{0,2000}?)</a>""",
            RegexOption.IGNORE_CASE
        )
        for (m in card.findAll(html)) {
            val noteId = m.groupValues[1]
            if (out.containsKey(noteId)) continue
            val query = m.groupValues[2]
            val inner = m.groupValues[3]
            val token = Regex("""xsec_token=([^&"'<]+)""").find(query)?.groupValues?.get(1)
            val cover = Regex("""<img[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
            val title = Regex(""">([^<>{}]{2,80})<""").findAll(inner)
                .map { it.groupValues[1].trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("http") }
            out[noteId] = ProfilePost(
                id = noteId,
                url = noteUrl(noteId, token),
                title = title,
                cover = cover,
                kind = if (inner.contains("video", ignoreCase = true)) PostKind.VIDEO else PostKind.UNKNOWN
            )
            if (out.size >= maxPosts) break
        }
        return out.values.toList()
    }

    /**
     * 从 WebView 钩子回收的 `user_posted` 分页响应里提取笔记。
     *
     * 回传格式：`JSON.stringify(window.__pages)`，即**字符串数组**，每个元素是一页响应原文
     * （内部 JSON 引号被转义）。实测虚拟列表在无窗口 WebView 里拿到数据也不渲染新卡片，
     * DOM 提不到，但这些响应里就是完整分页数据（note_id/display_title/cover/xsec_token…）。
     */
    private fun extractFromApiPages(input: String, ctx: ParseContext): List<ProfilePost> {
        // 回传串可能套了多层编码（JS 侧两层 JSON.stringify + evaluateJavascript 解码 +
        // 历史上还有经 DOM 序列化变成 &quot; 的形态）——逐层解码直到能切出笔记对象
        var text = HtmlUtil.unescapeHtmlOf(input)
        var posts = scanPages(text)
        var guard = 0
        while (posts.isEmpty() && guard++ < 4) {
            val next = stripOneLayer(text) ?: break
            text = HtmlUtil.unescapeHtmlOf(next)
            posts = scanPages(text)
        }
        ctx.log(id, "分页解码：层数=$guard 长度=${text.length} 笔记=${posts.size}")
        return posts
    }

    /** 剥掉一层 JSON 字符串包装（`"…"` → 内容按 JSON 转义还原）；纯 JVM 模块，不依赖 org.json */
    private fun stripOneLayer(s: String): String? {
        val t = s.trim()
        if (!t.startsWith("\"") || t.length < 3) return null
        val sb = StringBuilder(t.length)
        var i = 1
        while (i < t.length) {
            val c = t[i]
            when {
                c == '\\' && i + 1 < t.length -> {
                    val n = t[i + 1]
                    when (n) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'u' -> {
                            if (i + 5 < t.length) {
                                val hex = t.substring(i + 2, i + 6)
                                val cp = hex.toIntOrNull(16)
                                if (cp == null) return null
                                sb.append(cp.toChar()); i += 4
                            }
                        }
                        else -> return null
                    }
                    i++
                }
                c == '"' -> return sb.toString().takeIf { it.isNotBlank() }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }

    /** 扫描分页响应数组：每页是「JSON 字符串」形态，锚点兼容 `{"` 与 `{\"` 两种转义层 */
    private fun scanPages(pagesJson: String): List<ProfilePost> {
        val out = LinkedHashMap<String, ProfilePost>()
        var from = 0
        while (out.size < maxPosts) {
            val plain = pagesJson.indexOf("{\"", from)
            val escaped = pagesJson.indexOf("{\\\"", from)
            val open = when {
                plain < 0 && escaped < 0 -> break
                plain < 0 -> escaped
                escaped < 0 -> plain
                else -> minOf(plain, escaped)
            }
            val end = matchQuote(pagesJson, open) ?: break
            val page = pagesJson.substring(open, end + 1)
            from = end + 1

            // 页内层是转义 JSON：先还原，再按对象切 notes
            val body = HtmlUtil.unescapeJsonOf(page)
            val notes = arraySliceAfter(body, "\"notes\"")
            for (obj in jsonObjects(notes)) {
                val noteId = HtmlUtil.jsonField(obj, "note_id").firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: continue
                if (out.containsKey(noteId)) continue
                val token = HtmlUtil.jsonField(obj, "xsec_token").firstOrNull()?.takeIf { it.isNotBlank() }
                val cover = HtmlUtil.jsonField(obj, "url_default").firstOrNull()?.takeIf { it.startsWith("http") }
                    ?: HtmlUtil.jsonField(obj, "url_pre").firstOrNull()?.takeIf { it.startsWith("http") }
                val type = HtmlUtil.jsonField(obj, "type").firstOrNull().orEmpty()
                out[noteId] = ProfilePost(
                    id = noteId,
                    url = noteUrl(noteId, token),
                    title = HtmlUtil.jsonField(obj, "display_title").firstOrNull()?.takeIf { it.isNotBlank() },
                    cover = cover,
                    kind = if (type == "video") PostKind.VIDEO else PostKind.UNKNOWN,
                    publishedAt = HtmlUtil.jsonField(obj, "time").firstOrNull()?.toLongOrNull(),
                    likedCount = HtmlUtil.jsonField(obj, "liked_count").firstOrNull()?.toIntOrNull()
                )
                if (out.size >= maxPosts) break
            }
        }
        return out.values.toList()
    }

    /** 从 `open` 处的引号串找到未转义的收尾引号，返回其下标 */
    private fun matchQuote(text: String, open: Int): Int? {
        var p = open + 1
        while (p < text.length) {
            when (text[p]) {
                '\\' -> p++
                '"' -> return p
            }
            p++
        }
        return null
    }

    /** 笔记页链接：必须带 SSR/DOM 里的 xsecToken，裸 /explore/<id> 会被判无效 */
    private fun noteUrl(noteId: String, token: String?): String = buildString {
        append("https://www.xiaohongshu.com/explore/").append(noteId)
        if (!token.isNullOrBlank()) {
            // token 是 base64 形态（含 + = /），拼进 query 前必须转义，否则参数解析会截断
            val encoded = token.replace("+", "%2B").replace("=", "%3D").replace("/", "%2F")
            append("?xsec_token=").append(encoded).append("&xsec_source=pc_user")
        }
    }

    /** 按 UA 直连取页面；失败返回 null（由调用方决定是否降级） */
    private fun fetch(url: String, ctx: ParseContext, desktop: Boolean): String? {
        val headers = ctx.headersFor(platform, desktop = desktop) + mapOf(
            "Referer" to "https://www.xiaohongshu.com/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
        )
        return runCatching { ctx.http.get(url, headers) }.getOrNull()?.body
    }

    /**
     * 提取笔记卡片。
     *
     * SSR 里 `notes` 是二维数组，元素形如 `{"id":"<noteId>","noteCard":{...}}`；
     * 关键字段都在 `noteCard` 内部（`noteId`/`displayTitle`/`cover`/`xsecToken`/`type`），
     * 所以直接按 `"noteCard":` 定位并用括号配对切块即可，无需依赖外层结构。
     */
    private fun extractNoteCards(state: String): List<ProfilePost> {
        val out = LinkedHashMap<String, ProfilePost>()
        var from = 0
        while (out.size < maxPosts) {
            val idx = state.indexOf("\"noteCard\"", from)
            if (idx < 0) break
            val open = state.indexOf('{', idx)
            if (open < 0) break
            val end = matchBrace(state, open) ?: break
            val block = state.substring(open, end + 1)
            from = end + 1

            val noteId = HtmlUtil.jsonField(block, "noteId").firstOrNull()?.takeIf { it.isNotBlank() }
                ?: continue
            if (out.containsKey(noteId)) continue

            val cover = HtmlUtil.jsonField(block, "urlDefault").firstOrNull()
                ?.takeIf { it.startsWith("http") }
                ?: HtmlUtil.jsonField(block, "urlPre").firstOrNull()?.takeIf { it.startsWith("http") }
            val token = HtmlUtil.jsonField(block, "xsecToken").firstOrNull()?.takeIf { it.isNotBlank() }
            val type = HtmlUtil.jsonField(block, "type").firstOrNull().orEmpty()
            val liked = HtmlUtil.jsonField(block, "likedCount").firstOrNull()?.toIntOrNull()
            val time = HtmlUtil.jsonField(block, "time").firstOrNull()?.toLongOrNull()

            out[noteId] = ProfilePost(
                id = noteId,
                // 带上 xsecToken：裸 /explore/<id> 打开会被判无效
                url = noteUrl(noteId, token),
                title = HtmlUtil.jsonField(block, "displayTitle").firstOrNull()?.takeIf { it.isNotBlank() },
                cover = cover,
                kind = if (type == "video") PostKind.VIDEO else PostKind.UNKNOWN,
                publishedAt = time,
                likedCount = liked
            )
        }
        return out.values.toList()
    }

    /**
     * 头部统计。
     *
     * SSR 的 `interactions` 是 `[{type,name,count,i18nCount}]`，但**键序不固定**
     * （如 `{"count":"485979","name":"获赞与收藏"}` 的 count 在 name 之前）。
     * 只按"就近找 count"会串到隔壁对象——实测把"粉丝 3293"读成了"关注 36"。
     * 因此先按对象切开，**在同一个对象内**读 name 与 count。
     */
    private fun extractStats(profileWindow: String): ProfileStats? {
        val interactions = arraySliceAfter(profileWindow, "\"interactions\"")
        fun countFor(vararg names: String): Int? {
            for (obj in jsonObjects(interactions)) {
                val nm = HtmlUtil.jsonField(obj, "name").firstOrNull() ?: continue
                if (names.none { it == nm }) continue
                HtmlUtil.jsonField(obj, "count").firstOrNull()?.toIntOrNull()?.let { return it }
            }
            return null
        }
        val stats = ProfileStats(
            posts = HtmlUtil.jsonField(profileWindow, "posted").firstOrNull()?.toIntOrNull(),
            followers = countFor("粉丝"),
            following = countFor("关注"),
            likes = countFor("获赞与收藏", "获赞")
        )
        return if (stats == ProfileStats()) null else stats
    }

    /** 取 `key` 之后第一个 `[...]` 的切片（用于 interactions 这类数组字段） */
    private fun arraySliceAfter(text: String, key: String): String {
        // 必须匹配完整的 "key": 形式——曾因裸 indexOf("\"notes\"") 命中封面 URL 里的
        // "…/notes_pre_post/…"（同样含 "notes" 字样），把数组锚点找错导致整页解析为空
        var k = -1
        var from = 0
        while (true) {
            val i = text.indexOf(key, from)
            if (i < 0) break
            var j = i + key.length
            while (j < text.length && (text[j] == ' ' || text[j] == ':')) j++
            if (text.indexOf("[", j) == j) { k = i; break }
            from = i + key.length
        }
        if (k < 0) return ""
        val open = text.indexOf('[', k)
        if (open < 0) return ""
        var depth = 0
        var inStr = false
        var escaped = false
        for (p in open until text.length) {
            val c = text[p]
            when {
                escaped -> escaped = false
                c == '\\' && inStr -> escaped = true
                c == '"' -> inStr = !inStr
                !inStr && c == '[' -> depth++
                !inStr && c == ']' -> {
                    depth--
                    if (depth == 0) return text.substring(open, p + 1)
                }
            }
        }
        return ""
    }

    /** 把 `[{...},{...}]` 切成各个对象的切片（顶层括号配对） */
    private fun jsonObjects(arrayText: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < arrayText.length) {
            val open = arrayText.indexOf('{', i)
            if (open < 0) break
            val end = matchBrace(arrayText, open) ?: break
            out += arrayText.substring(open, end + 1)
            i = end + 1
        }
        return out
    }

    /** 取以 `anchor` 为中心的一段窗口，避免整页正则互相干扰 */
    private fun windowAround(text: String, anchor: String, before: Int, after: Int): String? {
        val i = text.indexOf(anchor)
        if (i < 0) return null
        val start = (i - before).coerceAtLeast(0)
        val end = (i + after).coerceAtMost(text.length)
        return text.substring(start, end)
    }

    /** 从 `open` 处的 `{` 开始做括号配对，返回对应 `}` 的下标 */
    private fun matchBrace(text: String, open: Int): Int? {
        var depth = 0
        var inStr = false
        var escaped = false
        for (p in open until text.length) {
            val c = text[p]
            when {
                escaped -> escaped = false
                c == '\\' && inStr -> escaped = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}'
                    -> {
                    depth--
                    if (depth == 0) return p
                }
            }
        }
        return null
    }

    /** 单页笔记数（SSR 首屏返回 30 余篇） */
    private companion object {
        const val PAGE_SIZE = 30

        /** WebView 回传串里「渲染 DOM」与「分页接口响应 JSON」的分隔标记 */
        const val PAGES_SEP = "<<<XHS_PAGES>>>"
    }
}

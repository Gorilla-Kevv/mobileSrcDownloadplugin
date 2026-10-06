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

    override fun parseProfile(url: String, handle: String, ctx: ParseContext): ProfileResult {
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
        val posts = extractNoteCards(state)
        ctx.log(
            id,
            "主页解析：via=$via handle=$handle 笔记=${posts.size} " +
                "state=${state.length} userPageData=${state.contains("userPageData")}"
        )

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
            warning = if (posts.size >= PAGE_SIZE) "当前展示 ${posts.size} 篇（第一页），更多请到站内翻页" else null
        )
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
        while (out.size < MAX_POSTS) {
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
                url = buildString {
                    append("https://www.xiaohongshu.com/explore/").append(noteId)
                    if (token != null) append("?xsec_token=").append(token).append("&xsec_source=pc_user")
                },
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
        val k = text.indexOf(key)
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
        const val MAX_POSTS = 60
    }
}

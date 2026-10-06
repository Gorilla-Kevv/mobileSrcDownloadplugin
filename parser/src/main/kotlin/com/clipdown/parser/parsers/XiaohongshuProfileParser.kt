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
 * 数据来源策略（与笔记链路同机制）：
 * - 小红书主页的笔记列表**不是 SSR**，而是页面 JS 通过带签名的 XHR
 *   （`/api/sns/web/v1/user_posted`）拉取后渲染进 DOM。自己重放该请求需要
 *   `x-s`/`x-t` 签名，成本高且随前端版本变化；因此这里走 **WebView 渲染 →
 *   从渲染后的 DOM 提取**，让页面自己的 JS 去完成签名与请求。
 * - 直连（移动端 UA）作为兜底：部分账号/网络下主页会返回带数据的 HTML。
 *
 * DOM 提取要点：笔记卡片是 `<a href="/explore/<noteId>">`，封面在其中的 `<img>`，
 * 标题在卡片的文本节点里。头像/昵称/简介属于页面头部，用 meta 与文本做尽力提取，
 * 拿不到就留空（UI 显示占位，不阻塞列表展示）。
 */
class XiaohongshuProfileParser : ProfileParser {

    override val platform: Platform = Platform.XIAOHONGSHU
    override val id: String = "xhs-profile-v1"

    override fun parseProfile(url: String, handle: String, ctx: ParseContext): ProfileResult {
        // 1) WebView 渲染优先：主页列表由 JS 拉取渲染，只有渲染后的 DOM 才有笔记卡片
        val rendered = runCatching { ctx.webFetcher?.invoke(url) }.getOrNull()

        var via = "webview"
        var html = rendered
        if (html.isNullOrBlank()) {
            // 2) 兜底：移动端 UA 直连（桌面 UA 会被 302 到 /login）
            via = "okhttp"
            val headers = ctx.headersFor(platform, desktop = false) + mapOf(
                "Referer" to "https://www.xiaohongshu.com/",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            html = runCatching { ctx.http.get(url, headers) }.getOrNull()?.body
        }

        val page = html ?: throw ParseException("主页内容为空（可能触发了风控）", platform)

        // 登录墙/失效守卫：与笔记链路一致，避免把"首页/登录页"当成主页
        val pageTitle = HtmlUtil.meta(page, "og:title") ?: HtmlUtil.title(page)
        if (pageTitle != null && pageTitle.contains("你的生活兴趣社区")) {
            throw ParseException(
                "小红书要求登录态（主页被跳到了首页/登录页）。请在「设置」中补充小红书 Cookie 后重试",
                platform,
                retryable = false
            )
        }

        val posts = extractNoteCards(page)
        ctx.log(id, "主页解析：via=$via handle=$handle 笔记=${posts.size} len=${page.length}")

        if (posts.isEmpty()) {
            throw ParseException(
                "未能从主页中提取到笔记列表（可能需要登录态，或页面结构已更新）",
                platform
            )
        }

        return ProfileResult(
            platform = platform,
            resolverId = id,
            sourceUrl = url,
            userId = handle,
            nickname = extractNickname(page),
            avatar = HtmlUtil.meta(page, "og:image"),
            bio = HtmlUtil.meta(page, "og:description")?.takeIf { it.isNotBlank() },
            stats = extractStats(page),
            posts = posts,
            hasMore = false,
            warning = if (posts.size >= MAX_POSTS) "仅展示前 $MAX_POSTS 篇（如需更多请在站内翻页后逐篇下载）" else null
        )
    }

    /**
     * 从渲染后的 DOM 提取笔记卡片。
     *
     * 兼容两种渲染形态：
     * - `<a href="/explore/<noteId>">`（PC/移动网页版）
     * - `<a href="/discovery/item/<noteId>">`（部分入口）
     * 卡片内的第一张 `<img>` 视为封面，卡片内文本视为标题。
     */
    private fun extractNoteCards(page: String): List<ProfilePost> {
        val out = LinkedHashMap<String, ProfilePost>()
        // 逐个 <a ...> 到 </a> 之间作为一张卡片（懒匹配，避免跨卡片吞内容）
        val cardRegex = Regex(
            """<a\s[^>]*href\s*=\s*["'](?:https?://[^"']*?)?/(?:explore|discovery/item)/([0-9a-zA-Z]+)[^"']*["'][^>]*>([\s\S]{0,2000}?)</a>""",
            RegexOption.IGNORE_CASE
        )
        for (m in cardRegex.findAll(page)) {
            val noteId = m.groupValues[1]
            if (out.containsKey(noteId)) continue
            val inner = m.groupValues[2]
            val cover = Regex("""<img[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(inner)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("http") }
            val title = Regex(""">([^<>{}]{2,80})<""")
                .findAll(inner)
                .map { it.groupValues[1].trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("http") }
            val hasVideo = inner.contains("video", ignoreCase = true) ||
                inner.contains("播放", ignoreCase = false)
            out[noteId] = ProfilePost(
                id = noteId,
                url = "https://www.xiaohongshu.com/explore/$noteId",
                title = title,
                cover = cover,
                kind = if (hasVideo) PostKind.VIDEO else PostKind.UNKNOWN
            )
            if (out.size >= MAX_POSTS) break
        }
        return out.values.toList()
    }

    private fun extractNickname(page: String): String? =
        HtmlUtil.meta(page, "og:title")?.substringBefore(" - 小红书")?.takeIf { it.isNotBlank() }
            ?: HtmlUtil.title(page)?.substringBefore(" - 小红书")?.takeIf { it.isNotBlank() }

    /** 头部统计（关注/粉丝/获赞）：渲染后的 DOM 里是"数字+文案"的相邻文本，尽力提取 */
    private fun extractStats(page: String): ProfileStats? {
        fun countNear(keyword: String): Int? {
            val m = Regex("""([0-9][0-9.,]*\s*[万亿]?)\s*(?:</[^>]+>\s*<[^>]+>\s*)?$keyword""")
                .find(page) ?: return null
            return parseCount(m.groupValues[1])
        }
        val stats = ProfileStats(
            posts = countNear("笔记"),
            followers = countNear("粉丝"),
            following = countNear("关注"),
            likes = countNear("获赞")
        )
        return if (stats == ProfileStats()) null else stats
    }

    /** "1.2万" / "3,456" / "12" → Int */
    internal fun parseCount(raw: String): Int? {
        val s = raw.replace(",", "").replace(" ", "").trim()
        if (s.isEmpty()) return null
        val unit = when {
            s.endsWith("亿") -> 100_000_000
            s.endsWith("万") -> 10_000
            else -> 1
        }
        val num = s.removeSuffix("亿").removeSuffix("万").toDoubleOrNull() ?: return null
        return (num * unit).toInt()
    }

    private companion object {
        /** 单次最多展示的笔记数（避免一次渲染过多卡片拖慢页面） */
        const val MAX_POSTS = 60
    }
}

package com.clipdown.parser.parsers

import com.clipdown.parser.model.Platform
import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.PostKind
import com.clipdown.parser.model.ProfilePost
import com.clipdown.parser.model.ProfileResult
import com.clipdown.parser.model.ProfileStats
import com.clipdown.parser.spi.ParseContext
import com.clipdown.parser.spi.ProfileParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Instagram 博主主页解析器。
 *
 * 三条通道按"信息量优先"降级（与单篇链路同源的经验：IG 一改版就只剩某一条还能用）：
 * 1. **web_profile_info 接口**（需登录 Cookie）→ 结构化 JSON，含封面/标题/视频标记/统计，一次约 12 篇；
 * 2. **主页 HTML**（桌面 UA + Cookie）→ `og:*` 元信息给昵称/简介/头像/粉丝数，帖子只扫得到链接；
 * 3. **WebView 渲染页**（Cookie 由 App 层注入）→ 同 2 的扫法，用于前两条被 JS 壳或风控挡掉时。
 *
 * 风控：所有通道都包在 [IgRiskGuard] 里——IG 对主页这类"整页列表"接口尤其敏感，
 * 连续失败会冷却 10 分钟且**期间一个请求都不发**，与单篇链路共享计数。
 */
class InstagramProfileParser : ProfileParser {

    override val platform: Platform = Platform.INSTAGRAM
    override val id: String = "ig-profile-v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun parseProfile(
        url: String,
        handle: String,
        ctx: ParseContext,
        pages: Int
    ): ProfileResult {
        // 缺登录态属于"用户还没配置"，不该计入风控失败——否则连点两次就把补好 Cookie 的用户锁在冷却里
        if (ctx.cookieProvider(platform).isNullOrBlank()) {
            throw ParseException(
                "Instagram 主页需要登录态。请在「设置 → Instagram Cookie」粘贴浏览器 Cookie 后重试",
                platform, retryable = false
            )
        }
        return IgRiskGuard.guard(ctx, id) { parseChannels(url, handle, ctx) }
    }

    private fun parseChannels(url: String, handle: String, ctx: ParseContext): ProfileResult {
        val cookie = ctx.cookieProvider(platform) ?: ""
        val attempts = mutableListOf<String>()

        // 1) web_profile_info：结构化数据最全
        fromApi(handle, cookie, ctx)?.let {
            ctx.log(id, "主页 via=api 笔记=${it.posts.size}")
            return it
        }
        attempts += "api"

        // 2) 主页 HTML
        fromHtml(url, cookie, ctx)?.let {
            ctx.log(id, "主页 via=html 笔记=${it.posts.size}")
            return it
        }
        attempts += "html"

        // 3) WebView 渲染兜底
        val rendered = runCatching { ctx.webFetcher?.invoke(url) }.getOrNull()
        if (!rendered.isNullOrBlank()) {
            fromPage(rendered, handle)?.let {
                if (it.posts.isNotEmpty()) {
                    ctx.log(id, "主页 via=webview 笔记=${it.posts.size}")
                    return it
                }
            }
        }
        attempts += "webview"

        throw ParseException(
            "Instagram 主页解析失败（已试 ${attempts.joinToString("→")}）。" +
                "多为账号风控或 Cookie 失效，请勿连续重试，等冷却结束后再试",
            platform
        )
    }

    // ────────────────────────── 通道 1：web_profile_info ──────────────────────────

    private fun fromApi(handle: String, cookie: String, ctx: ParseContext): ProfileResult? {
        val api = "https://i.instagram.com/api/v1/users/web_profile_info/?username=$handle"
        val headers = ctx.headersFor(platform) + mapOf(
            "Cookie" to cookie,
            "x-ig-app-id" to IG_APP_ID,
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://www.instagram.com/$handle/"
        )
        val body = runCatching { ctx.http.get(api, headers) }
            .getOrNull()
            ?.takeIf { it.code < 400 }
            ?.body
            ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val user = root.dig("data", "user") as? JsonObject ?: return null

        val posts = LinkedHashMap<String, ProfilePost>()
        val edges = user.dig("edge_owner_to_timeline_media", "edges") as? JsonArray
        for (edge in edges.orEmpty()) {
            val node = edge?.jsonObject?.get("node") as? JsonObject ?: continue
            val code = node.str("shortcode") ?: continue
            if (posts.containsKey(code)) continue
            val sidecar = node.dig("edge_sidecar_to_children", "edges") as? JsonArray
            posts[code] = ProfilePost(
                id = code,
                url = postUrl(code, node.bool("is_video") == true),
                title = (node.dig("edge_media_to_caption", "edges") as? JsonArray)
                    ?.firstOrNull()?.jsonObject?.dig("node", "text")?.strValue()
                    ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(120)
                    ?: node.str("title")?.takeIf { it.isNotBlank() },
                cover = node.str("display_url"),
                kind = when {
                    node.bool("is_video") == true -> PostKind.VIDEO
                    (sidecar?.size ?: 0) > 1 -> PostKind.ALBUM
                    sidecar != null -> PostKind.IMAGE
                    else -> PostKind.UNKNOWN
                },
                mediaCount = sidecar?.size?.takeIf { it > 0 },
                likedCount = countOf(node.dig("edge_liked_by") as? JsonObject, "count")
            )
        }
        if (posts.isEmpty()) return null

        return baseResult(handle).copy(
            userId = user.str("id"),
            nickname = user.str("full_name")?.takeIf { it.isNotBlank() },
            bio = user.str("biography")?.takeIf { it.isNotBlank() },
            avatar = user.str("profile_pic_url_hd") ?: user.str("profile_pic_url"),
            stats = ProfileStats(
                posts = countOf(user, "edge_owner_to_timeline_media", "count"),
                followers = countOf(user, "edge_followed_by", "count"),
                following = (user.dig("follow") as? JsonObject).let { countOf(it ?: user, "edge_follow", "count") },
                likes = null
            ),
            posts = posts.values.toList(),
            hasMore = false,
            warning = "公开接口单次仅提供最近 ${posts.size} 篇"
        )
    }

    // ────────────────────────── 通道 2/3：页面（HTML 或渲染后 DOM） ──────────────────────────

    private fun fromHtml(url: String, cookie: String, ctx: ParseContext): ProfileResult? {
        val headers = ctx.headersFor(platform) + mapOf(
            "Cookie" to cookie,
            "Referer" to "https://www.instagram.com/",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        val html = runCatching { ctx.http.get(url, headers) }
            .getOrNull()
            ?.takeIf { it.code < 400 }
            ?.body
            ?: return null
        return fromPage(html, null)?.takeIf { it.posts.isNotEmpty() }
    }

    /**
     * 从页面里扫帖子链接 + `og:*` 元信息。
     *
     * 不依赖 IG 的内部 JSON（`window._data` 结构常年变动且转义复杂），
     * 只取稳定的公开标记：`href="/p/<code>/"`、`/reel/`、`/tv/`，以及 og:title/og:description/og:image。
     */
    private fun fromPage(html: String, fallbackHandle: String?): ProfileResult? {
        val posts = LinkedHashMap<String, ProfilePost>()
        // 帖子链接形态：/p/<code>/、/reel/<code>/、/tv/<code>/，可能带 query（?utm_source=…）
        val link = Regex(
            """href=["'](?:https?://www\.instagram\.com)?/(p|reel|tv)/([A-Za-z0-9_-]{5,})["'/?]""",
            RegexOption.IGNORE_CASE
        )
        for (m in link.findAll(html)) {
            val code = m.groupValues[2]
            if (posts.containsKey(code)) continue
            val kind = if (m.groupValues[1].equals("reel", true)) PostKind.VIDEO else PostKind.UNKNOWN
            posts[code] = ProfilePost(
                id = code,
                url = "https://www.instagram.com/${m.groupValues[1].lowercase()}/$code/",
                cover = coverFor(html, code),
                kind = kind
            )
        }

        val ogTitle = HtmlUtil.meta(html, "og:title") ?: HtmlUtil.title(html)
        val handle = Regex("""\(@([A-Za-z0-9._]{2,40})\)""").find(ogTitle ?: "")?.groupValues?.get(1)
            ?: fallbackHandle ?: return null
        if (posts.isEmpty() && ogTitle.isNullOrBlank()) return null

        val desc = HtmlUtil.meta(html, "og:description").orEmpty()
        fun num(vararg keys: String): Int? {
            for (k in keys) {
                val raw = Regex("""([\d.,]+[KMB]?)\s*$k""", RegexOption.IGNORE_CASE)
                    .find(desc)?.groupValues?.get(1)
                val parsed = raw?.let { parseCompactCount(it) }
                if (parsed != null) return parsed
            }
            return null
        }
        return baseResult(handle).copy(
            nickname = ogTitle?.substringBefore("(")?.trim()?.takeIf { it.isNotEmpty() },
            bio = desc.substringAfter(" - ", "").substringBefore("Insta").takeIf { it.isNotBlank() && it != desc },
            avatar = HtmlUtil.meta(html, "og:image")?.takeIf { it.startsWith("http") },
            stats = ProfileStats(
                posts = num("Posts", "帖子"),
                followers = num("Followers", "粉丝"),
                following = num("Following", "关注")
            ),
            posts = posts.values.toList(),
            hasMore = false,
            warning = if (posts.isNotEmpty()) "页面通道仅能拿到链接，封面与标题可能缺失" else null
        )
    }

    /**
     * 找某个帖子在页面里的封面图。
     *
     * IG 的图片 URL 是 `…/v/t51.2885-15/<code>_n.jpg?_nc_cat=…` 这种形态——
     * code 后面跟的是 `_n.` 而不是 `/`，所以只能按"URL 里含该 code"来配对，
     * 不能按路径段匹配。
     */
    private fun coverFor(html: String, code: String): String? {
        val raw = Regex("""["']([^"']*$code[^"']*\.(?:jpg|jpeg|png|webp)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: return null
        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://www.instagram.com$raw"
            else -> null
        }
    }

    private fun baseResult(handle: String) = ProfileResult(
        platform = platform,
        resolverId = id,
        sourceUrl = "https://www.instagram.com/$handle/",
        userId = handle
    )

    private fun postUrl(code: String, video: Boolean): String =
        "https://www.instagram.com/${if (video) "reel" else "p"}/$code/"

    /** "80.4M" / "1,234" / "12K" → 整数 */
    private fun parseCompactCount(raw: String): Int? {
        val s = raw.trim().replace(",", "").uppercase()
        val m = Regex("""^([\d.]+)\s*([KMB]?)$""").find(s) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2]) {
            "K" -> 1_000; "M" -> 1_000_000; "B" -> 1_000_000_000; else -> 1
        }
        return (value * mult).toInt()
    }

    private fun JsonObject.dig(vararg keys: String): JsonElement? {
        var cur: JsonElement = this
        for (k in keys) {
            cur = (cur as? JsonObject)?.get(k) ?: return null
        }
        return cur
    }

    private fun JsonElement?.strValue(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.bool(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonElement?.intLeaf(): Int? = (this as? JsonPrimitive)?.intOrNull

    /** 取 `obj[key].count`（IG 的计数都包在这一层里） */
    private fun countOf(obj: JsonObject?, vararg path: String): Int? {
        if (obj == null) return null
        return obj.dig(*path).intLeaf()
    }

    private companion object {
        /** Instagram Web 的固定 app id（公开值，网页端本身就带着） */
        const val IG_APP_ID = "936619743392459"
    }
}

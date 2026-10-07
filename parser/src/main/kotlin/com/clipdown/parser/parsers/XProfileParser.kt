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
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * X（Twitter）博主主页解析器。
 *
 * 数据来源是 X 的公开嵌入时间线接口（**免登录**，与单篇用的 syndication 同族）：
 * `https://syndication.twitter.com/srv/timeline-profile/screen-name/<handle>`
 * 返回一页 Next.js 页面，推文列表内嵌在 `<script id="__NEXT_DATA__">` 的
 * `props.pageProps.timeline.entries[]` 里，每条含完整 `user` 对象与 `entities.media[]`。
 *
 * 已知边界（2026-10 实测）：该接口只给**最近约 100 条**、没有游标，
 * 所以 [ProfileResult.hasMore] 恒为 false，受保护/不存在账号会给出空时间线。
 */
class XProfileParser : ProfileParser {

    override val platform: Platform = Platform.X
    override val id: String = "x-syndication-profile-v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun parseProfile(
        url: String,
        handle: String,
        ctx: ParseContext,
        pages: Int
    ): ProfileResult {
        val api = "https://syndication.twitter.com/srv/timeline-profile/screen-name/$handle"
        val headers = ctx.headersFor(platform) + mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "https://twitter.com/"
        )
        val resp = runCatching { ctx.http.get(api, headers) }
            .getOrElse { throw ParseException("网络请求失败：${it.message}", platform) }
        val html = resp.body
            ?: throw ParseException("公开时间线返回空内容", platform)
        if (resp.code >= 400) throw ParseException("公开时间线返回 ${resp.code}", platform)

        val data = nextData(html)
            ?: throw ParseException(
                "未能读取该账号的公开时间线（账号可能不存在、已改名或受保护）",
                platform, retryable = false
            )
        val root = runCatching { json.parseToJsonElement(data).jsonObject }
            .getOrNull()
            ?: throw ParseException("时间线数据结构异常", platform)

        val entries = root.dig("props", "pageProps", "timeline", "entries") as? JsonArray
        if (entries.isNullOrEmpty()) {
            throw ParseException(
                "该账号没有公开时间线（受保护推文或尚未发过内容）",
                platform, retryable = false
            )
        }

        val tweets = entries.mapNotNull { it?.jsonObject?.dig("content", "tweet") as? JsonObject }
        val user = tweets.firstOrNull()?.get("user") as? JsonObject
        val screenName = user.str("screen_name") ?: handle

        val posts = LinkedHashMap<String, ProfilePost>()
        for (t in tweets) {
            // 转发条取被转发原推：链接与媒体都在原推上，否则点开只会拿到转发壳
            val src = t.dig("retweeted_status") as? JsonObject ?: t
            val tweetId = src.str("id_str") ?: continue
            if (posts.containsKey(tweetId)) continue
            posts[tweetId] = toPost(tweetId, src, screenName, t !== src)
            if (posts.size >= MAX_POSTS) break
        }

        val list = posts.values.toList()
        if (list.isEmpty()) {
            throw ParseException("时间线里解析不到任何推文", platform)
        }
        ctx.log(id, "X 主页：$screenName 推文=${list.size} 条目=${entries.size}")

        return ProfileResult(
            platform = platform,
            resolverId = id,
            sourceUrl = url,
            userId = user.str("id_str"),
            nickname = user.str("name"),
            // _normal 只有 48px，换 400px 档做信息卡头像
            avatar = (user.str("profile_image_url_https") ?: user.str("profile_image_url"))
                ?.replace("_normal.", "_400x400."),
            bio = user.str("description")?.takeIf { it.isNotBlank() },
            stats = ProfileStats(
                posts = user.int("statuses_count"),
                followers = user.int("followers_count"),
                following = user.int("friends_count")
            ),
            posts = list,
            hasMore = false,
            warning = if (list.size >= MAX_POSTS) "公开时间线仅提供最近约 $MAX_POSTS 条" else null
        )
    }

    private fun toPost(tweetId: String, t: JsonObject, owner: String, isRetweet: Boolean): ProfilePost {
        val media = (t.dig("entities", "media") as? JsonArray)
            ?.mapNotNull { it?.jsonObject }
            .orEmpty()
        val type = media.firstOrNull()?.str("type")
        val kind = when {
            type == "video" || type == "animated_gif" -> PostKind.VIDEO
            media.size > 1 -> PostKind.ALBUM
            media.size == 1 -> PostKind.IMAGE
            else -> PostKind.UNKNOWN
        }
        val text = (t.str("full_text") ?: t.str("text")).orEmpty()
            .replace(Regex("""\s*https://t\.co/\w+\s*$"""), "")   // 结尾的分享短链不是正文
            .trim()
        return ProfilePost(
            id = tweetId,
            url = "https://x.com/$owner/status/$tweetId",
            title = when {
                text.isNotBlank() -> (if (isRetweet) "转发 · " else "") + text.take(120)
                isRetweet -> "转发的内容"
                else -> null
            },
            cover = media.firstOrNull()?.str("media_url_https"),
            kind = kind,
            mediaCount = media.size.takeIf { it > 0 },
            publishedAt = parseTweetTime(t.str("created_at")),
            likedCount = t.int("favorite_count")
        )
    }

    /** X 的时间格式固定为 `Sat Jul 13 22:51:28 +0000 2024` */
    private fun parseTweetTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US).parse(raw)?.time
        }.getOrNull()
    }

    /** 取 `<script id="__NEXT_DATA__" type="application/json">…</script>` 里的 JSON */
    private fun nextData(html: String): String? {
        val i = html.indexOf("__NEXT_DATA__")
        if (i < 0) return null
        val open = html.indexOf('>', i)
        if (open < 0) return null
        val close = html.indexOf("</script>", open)
        if (close < 0) return null
        return html.substring(open + 1, close).trim().takeIf { it.startsWith("{") }
    }

    /** 逐级下钻，任一级缺失即返回 null（时间线字段层级深且经常缺字段） */
    private fun JsonObject.dig(vararg keys: String): JsonElement? {
        var cur: JsonElement = this
        for (k in keys) {
            cur = (cur as? JsonObject)?.get(k) ?: return null
        }
        return cur
    }

    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.int(key: String): Int? =
        (this?.get(key) as? JsonPrimitive)?.intOrNull

    private companion object {
        /** 公开时间线一次最多给约 100 条 */
        const val MAX_POSTS = 100
    }
}

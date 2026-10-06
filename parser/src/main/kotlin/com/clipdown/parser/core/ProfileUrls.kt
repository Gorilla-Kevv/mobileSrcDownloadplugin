package com.clipdown.parser.core

import com.clipdown.parser.model.Platform

/** 链接形态 */
enum class LinkKind {
    /** 单篇作品页 */
    POST,

    /** 博主主页 */
    PROFILE,

    /** 无法判定 */
    UNKNOWN
}

/** 主页识别结果 */
data class ProfileMatch(val platform: Platform, val handle: String)

/**
 * 主页链接识别。
 *
 * 各平台的主页 URL 形态差异很大（有的靠路径、有的靠子域），
 * 且**最容易误判**：Instagram 的 `/p/xxx` 与 `/<username>` 只差一段路径。
 * 因此这里对每个平台显式列出"保留段"（post/reel/explore/home…），
 * 命中保留段就判为作品页，否则才按主页处理。
 */
object ProfileUrls {

    /** 非用户名的保留路径段（小写比较） */
    private val RESERVED = setOf(
        "p", "reel", "reels", "stories", "tv", "explore", "exploretag", "accounts",
        "direct", "about", "legal", "developer", "web", "i", "home", "search",
        "notifications", "messages", "settings", "compose", "intent", "share",
        "status", "statuses", "hashtag", "video", "note", "slides", "detail",
        "bangumi", "list", "watch", "shorts", "live", "feed", "login", "signup",
        "user", "users", "u", "profile", "channel", "c", "space", "fav", "watchlater"
    )

    /** 用户名允许的字符（各平台通用近似） */
    private val HANDLE = Regex("""^[A-Za-z0-9._\-@]+$""")

    /** 按平台识别主页；返回 null 表示不是主页链接 */
    fun match(url: String): ProfileMatch? {
        val platform = UrlUtil.detectPlatform(url) ?: return null
        val host = UrlUtil.hostOf(url)
        val path = UrlUtil.pathOf(url)
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        return when (platform) {
            // 小红书：/user/profile/<userId>
            Platform.XIAOHONGSHU -> {
                val i = segments.indexOfFirst { it == "profile" }
                if (i >= 1 && segments.getOrNull(i - 1) == "user") {
                    segments.getOrNull(i + 1)?.takeIf { it.isNotBlank() }?.let { ProfileMatch(platform, it) }
                } else null
            }

            // 微博：/u/<uid> 或 /<uid>
            Platform.WEIBO -> when {
                segments.firstOrNull() == "u" && segments.size >= 2 && segments[1].all { it.isDigit() } ->
                    ProfileMatch(platform, segments[1])

                segments.size == 1 && segments[0].length >= 6 && segments[0].all { it.isDigit() } ->
                    ProfileMatch(platform, segments[0])

                else -> null
            }

            // B 站：space.bilibili.com/<mid>
            Platform.BILIBILI ->
                if (host.startsWith("space.") && segments[0].all { it.isDigit() }) {
                    ProfileMatch(platform, segments[0])
                } else null

            // 抖音：/user/<sec_uid>
            Platform.DOUYIN ->
                if (segments.firstOrNull() == "user") {
                    segments.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { ProfileMatch(platform, it) }
                } else null

            // TikTok：/@<user>
            Platform.TIKTOK ->
                segments.firstOrNull()?.takeIf { it.startsWith("@") }?.removePrefix("@")
                    ?.takeIf { it.isNotBlank() }?.let { ProfileMatch(platform, it) }

            // YouTube：/@handle、/channel/<id>、/c/<name>、/user/<name>
            Platform.YOUTUBE -> when {
                segments[0].startsWith("@") -> ProfileMatch(platform, segments[0].removePrefix("@"))
                segments.size >= 2 && segments[0] in setOf("channel", "c", "user") ->
                    ProfileMatch(platform, segments[1])

                else -> null
            }

            // Instagram / X：/<handle>（单段且不是保留段）
            Platform.INSTAGRAM, Platform.X ->
                if (segments.size == 1 &&
                    segments[0].lowercase() !in RESERVED &&
                    HANDLE.matches(segments[0]) &&
                    !segments[0].startsWith("@")
                ) {
                    ProfileMatch(platform, segments[0])
                } else null

            else -> null
        }
    }

    /** 是否为主页链接 */
    fun isProfile(url: String): Boolean = match(url) != null

    /** 链接形态：主页 / 作品页 */
    fun kindOf(url: String): LinkKind = when {
        match(url) != null -> LinkKind.PROFILE
        UrlUtil.detectPlatform(url) != null -> LinkKind.POST
        else -> LinkKind.UNKNOWN
    }
}

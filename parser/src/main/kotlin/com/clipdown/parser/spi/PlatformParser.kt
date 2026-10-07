package com.clipdown.parser.spi

import com.clipdown.parser.config.ParserConfig
import com.clipdown.parser.http.HttpFacade
import com.clipdown.parser.model.MediaItem
import com.clipdown.parser.model.ParseResult
import com.clipdown.parser.model.Platform

/**
 * 解析过程中的上下文。
 *
 * 解析内核刻意不持有任何 Android 类型，方便在 JVM 上直接跑解析用例；
 * Android 侧只需提供 [cookieProvider] 即可把登录态注入需要 Cookie 的平台。
 */
class ParseContext(
    val config: ParserConfig,
    val http: HttpFacade,
    /** 按平台返回 Cookie 字符串；不需要登录的平台返回 null */
    val cookieProvider: (Platform) -> String? = { null },
    val logger: ((String, String) -> Unit)? = null,
    /**
     * 真浏览器抓取能力（App 层以 WebView 实现注入，内核保持纯 JVM）。
     * 用于 WAF 指纹拦截（小红书）与 JS 壳页（Instagram）等 OkHttp 无法直取的场景。
     * 返回渲染完成后的页面 HTML；失败返回 null。
     */
    val webFetcher: ((url: String) -> String?)? = null,
    /**
     * 带滚动加载的真浏览器抓取（App 层以 WebView 实现）。
     *
     * 用途：主页/列表的"加载更多"——分页接口通常需要签名，App 侧无法重放，
     * 但页面自己的 JS 能签名；让它在真浏览器里滚动触发无限滚动，再取回渲染后的 DOM。
     *
     * @param scrollTimes 滚到底部的次数（每滚一次通常多加载一页）
     * @param desktop 强制桌面 UA：部分平台（如小红书主页）只在桌面端下发完整 DOM
     */
    val webFetcherScroll: ((url: String, scrollTimes: Int, desktop: Boolean) -> String?)? = null
) {
    fun log(tag: String, msg: String) = logger?.invoke(tag, msg)

    fun ua(desktop: Boolean = true): String =
        if (desktop) config.userAgents.desktop else config.userAgents.mobile

    fun baseHeaders(desktop: Boolean = true): Map<String, String> = buildMap {
        put("User-Agent", ua(desktop))
        put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
    }

    fun headersFor(platform: Platform, desktop: Boolean = true): Map<String, String> {
        val base = baseHeaders(desktop).toMutableMap()
        cookieProvider(platform)?.takeIf { it.isNotBlank() }?.let { base["Cookie"] = it }
        return base
    }
}

/**
 * 单平台解析器接口。新增平台 = 新增一个实现类并注册进 [com.clipdown.parser.core.PlatformRegistry]。
 */
interface PlatformParser {

    val platform: Platform

    /** 解析器标识，写入 ParseResult.resolverId，用于日志与统计 */
    val id: String

    /** 该解析器是否处理这条 URL（已由路由层做过平台判定，这里做细粒度判断） */
    fun canHandle(url: String): Boolean

    /**
     * 执行解析。
     *
     * 约定：
     * - 拿不到资源时抛 [com.clipdown.parser.model.ParseException]，由上层决定降级；
     * - 拿到部分资源但体验受损（如需登录只能拿低清）时返回带 warning 的 ParseResult。
     */
    fun parse(url: String, ctx: ParseContext): ParseResult
}

/** 解析器通用工具：减少各平台实现里的重复代码 */
object ParserDsl {

    fun item(
        url: String,
        kind: com.clipdown.parser.model.MediaKind,
        quality: String,
        rank: Int = 0,
        mime: String? = null,
        container: String? = null,
        width: Int? = null,
        height: Int? = null,
        headers: Map<String, String> = emptyMap(),
        isPlaylist: Boolean = false,
        audioUrl: String? = null,
        needsRemux: Boolean = false
    ): MediaItem = MediaItem(
        id = url.hashCode().toString(16),
        url = url,
        kind = kind,
        quality = quality,
        rank = rank,
        mimeType = mime,
        container = container,
        width = width,
        height = height,
        headers = headers,
        isPlaylist = isPlaylist,
        audioUrl = audioUrl,
        needsRemux = needsRemux
    )

    fun result(
        platform: Platform,
        resolverId: String,
        sourceUrl: String,
        media: List<MediaItem>,
        title: String? = null,
        author: String? = null,
        cover: String? = null,
        source: com.clipdown.parser.model.ParseSource = com.clipdown.parser.model.ParseSource.LOCAL,
        warning: String? = null
    ): ParseResult = ParseResult(
        platform = platform,
        resolverId = resolverId,
        sourceUrl = sourceUrl,
        title = title,
        author = author,
        coverUrl = cover,
        media = media,
        source = source,
        warning = warning
    )
}

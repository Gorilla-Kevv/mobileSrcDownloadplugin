package com.clipdown.parser.core

import com.clipdown.parser.config.ParserConfig
import com.clipdown.parser.http.HttpFacade
import com.clipdown.parser.http.OkHttpFacade
import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.ParseResult
import com.clipdown.parser.model.ParseSource
import com.clipdown.parser.model.Platform
import com.clipdown.parser.parsers.BilibiliParser
import com.clipdown.parser.parsers.DouyinParser
import com.clipdown.parser.parsers.FacebookParser
import com.clipdown.parser.parsers.GenericParser
import com.clipdown.parser.parsers.InstagramParser
import com.clipdown.parser.parsers.TiktokParser
import com.clipdown.parser.parsers.WeiboParser
import com.clipdown.parser.parsers.XParser
import com.clipdown.parser.parsers.XiaohongshuParser
import com.clipdown.parser.parsers.YoutubeParser
import com.clipdown.parser.spi.ParseContext
import com.clipdown.parser.spi.PlatformParser

/**
 * 解析内核门面。
 *
 * 对上层只暴露两个动作：
 * - [parse]：给定链接，产出统一结构的 [ParseResult]
 * - [parseText]：给定剪贴板文本，先抽链接再解析
 *
 * 内核内部维护一条降级链：
 * 本地直连解析 → 平台公开接口 → 远端兜底服务。
 * 任一环节成功即返回，全部失败时抛出带业务语义的 [ParseException]。
 */
object ParserEngine {

    @Volatile
    private var config: ParserConfig = ParserConfig.default()

    @Volatile
    private var http: HttpFacade = OkHttpFacade()

    @Volatile
    private var cookieProvider: (Platform) -> String? = { null }

    @Volatile
    private var webFetcher: ((url: String) -> String?)? = null

    @Volatile
    private var logger: ((String, String) -> Unit)? = null

    @Volatile
    private var bootstrapped = false

    /**
     * 初始化。Android 侧在 Application.onCreate 调用一次。
     *
     * @param androidContext 仅作占位与后续扩展（如读取 UA），内核不直接使用 Android API
     */
    @Synchronized
    fun bootstrap(
        androidContext: Any? = null,
        config: ParserConfig = ParserConfig.default(),
        http: HttpFacade? = null,
        cookieProvider: (Platform) -> String? = { null },
        logger: ((String, String) -> Unit)? = null,
        webFetcher: ((url: String) -> String?)? = null
    ) {
        this.config = config
        this.http = http ?: OkHttpFacade(config.connectTimeoutMs, config.readTimeoutMs)
        this.cookieProvider = cookieProvider
        this.logger = logger
        this.webFetcher = webFetcher
        if (!bootstrapped) {
            PlatformRegistry.registerAll(defaultParsers())
            bootstrapped = true
        }
        androidContext?.let { this.logger?.invoke("ParserEngine", "bootstrapped with ${System.identityHashCode(it)}") }
    }

    /** 运行时热更新配置（设置页改动后立即生效） */
    fun updateConfig(newConfig: ParserConfig) {
        config = newConfig
        http = OkHttpFacade(newConfig.connectTimeoutMs, newConfig.readTimeoutMs)
    }

    fun currentConfig(): ParserConfig = config

    fun defaultParsers(): List<PlatformParser> = listOf(
        XiaohongshuParser(),
        InstagramParser(),
        XParser(),
        FacebookParser(),
        TiktokParser(),
        DouyinParser(),
        BilibiliParser(),
        WeiboParser(),
        YoutubeParser(),
        GenericParser()
    )

    private fun context(): ParseContext = ParseContext(config, http, cookieProvider, logger, webFetcher)

    /** 从剪贴板文本中解析：先抽链接，再走完整链路 */
    fun parseText(text: String?): ParseResult {
        val url = UrlUtil.normalize(
            UrlUtil.extractFirst(text) ?: throw ParseException("剪贴板中没有可识别的链接", retryable = false)
        )
        return parse(url)
    }

    /**
     * 解析主流程：
     * 1. 展开短链（xhslink / t.co / youtu.be / b23.tv / v.douyin.com ...）
     * 2. 判定平台并校验是否在用户勾选的监听范围内
     * 3. 路由到对应解析器
     * 4. 失败则依次降级到远端兜底与通用解析
     */
    fun parse(rawUrl: String): ParseResult {
        var url = UrlUtil.normalize(rawUrl)
        var platform = UrlUtil.detectPlatform(url)
            ?: throw ParseException("暂不支持该链接：$url", retryable = false)

        if (!config.isEnabled(platform)) {
            throw ParseException("${platform.displayName} 未开启监听", platform, retryable = false)
        }

        // 1) 短链展开：展开后平台可能变化（如 xhslink -> xiaohongshu）
        if (UrlUtil.isShortLink(url)) {
            val expanded = runCatching { expand(url) }.getOrNull()
            if (!expanded.isNullOrBlank()) {
                url = UrlUtil.normalize(expanded)
                UrlUtil.detectPlatform(url)?.let { platform = it }
            }
        }

        // 2) 本地 / 公开接口解析
        val local = runCatching {
            val parser = PlatformRegistry.resolveForUrl(url, platform)
                ?: throw ParseException("没有可用的解析器", platform, retryable = false)
            parser.parse(url, context())
        }

        val localResult = local.getOrNull()
        if (localResult != null && !localResult.isEmpty) {
            return localResult
        }
        val localError = local.exceptionOrNull()
        localError?.let { logger?.invoke("ParserEngine", "本地解析失败: ${it.message}") }

        // 3) 远端兜底
        val remote = RemoteResolver(config, http)
        if (remote.available) {
            val remoteResult = runCatching { remote.parse(url, platform) }.getOrNull()
            if (remoteResult != null && !remoteResult.isEmpty) return remoteResult
        }

        // 4) 通用网页兜底（抓 og:video / og:image）
        val generic = runCatching { GenericParser().parse(url, context()) }.getOrNull()
        if (generic != null && !generic.isEmpty) {
            return generic.copy(warning = generic.warning ?: "按通用网页解析，清晰度可能受限")
        }

        if (localError is ParseException) throw localError
        throw ParseException(
            localError?.message ?: "解析失败，请稍后重试或配置远端解析服务",
            platform,
            retryable = true,
            cause = localError
        )
    }

    /** 短链展开：优先 HEAD 取 Location，失败时用 GET 跟随重定向的最终 URL */
    fun expand(url: String): String? {
        var current = url
        repeat(config.maxRedirect) {
            val resp = runCatching {
                http.head(current, mapOf("User-Agent" to config.userAgents.desktop))
            }.getOrNull()
            val location = resp?.header("Location")
            if (location.isNullOrBlank()) {
                val getResp = runCatching {
                    http.get(current, mapOf("User-Agent" to config.userAgents.desktop))
                }.getOrNull()
                val final = getResp?.finalUrl
                return if (final.isNullOrBlank() || final == current) current else final
            }
            current = if (location.startsWith("http")) location else UrlUtil.normalize(location)
        }
        return current
    }

    /** 仅做平台判定，不做网络请求：供悬浮窗做"是否值得弹窗"的快速判断 */
    fun quickDetect(text: String?): Pair<String, Platform>? {
        val url = UrlUtil.extractFirst(text) ?: return null
        val normalized = UrlUtil.normalize(url)
        val platform = UrlUtil.detectPlatform(normalized) ?: return null
        if (!config.isEnabled(platform)) return null
        return normalized to platform
    }

    /** 预览态解析：供 UI 在后台线程调用，结果以 Result 暴露，便于统一处理失败 */
    fun parseSafe(rawUrl: String): Result<ParseResult> = runCatching { parse(rawUrl) }

    /** 解析来源的中文说明，UI 直接展示 */
    fun sourceLabel(source: ParseSource): String = when (source) {
        ParseSource.LOCAL -> "本地解析"
        ParseSource.OFFICIAL -> "平台公开接口"
        ParseSource.REMOTE -> "远端解析服务"
        ParseSource.SNIFFER -> "页面嗅探"
    }
}

package com.clipdown.parser.core

import com.clipdown.parser.config.ParserConfig
import com.clipdown.parser.http.HttpFacade
import com.clipdown.parser.http.OkHttpFacade
import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.ParseResult
import com.clipdown.parser.model.ParseSource
import com.clipdown.parser.model.Platform
import com.clipdown.parser.model.ProfileResult
import com.clipdown.parser.parsers.BilibiliParser
import com.clipdown.parser.parsers.DouyinParser
import com.clipdown.parser.parsers.FacebookParser
import com.clipdown.parser.parsers.GenericParser
import com.clipdown.parser.parsers.InstagramParser
import com.clipdown.parser.parsers.InstagramProfileParser
import com.clipdown.parser.parsers.TiktokParser
import com.clipdown.parser.parsers.WeiboParser
import com.clipdown.parser.parsers.XParser
import com.clipdown.parser.parsers.XProfileParser
import com.clipdown.parser.parsers.XiaohongshuParser
import com.clipdown.parser.parsers.XiaohongshuProfileParser
import com.clipdown.parser.parsers.YoutubeParser
import com.clipdown.parser.spi.ParseContext
import com.clipdown.parser.spi.PlatformParser
import com.clipdown.parser.spi.ProfileParser

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
    private var webFetcherScroll: ((url: String, scrollTimes: Int, desktop: Boolean) -> String?)? = null

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
        webFetcher: ((url: String) -> String?)? = null,
        webFetcherScroll: ((url: String, scrollTimes: Int, desktop: Boolean) -> String?)? = null
    ) {
        this.config = config
        this.http = http ?: OkHttpFacade(config.connectTimeoutMs, config.readTimeoutMs)
        this.cookieProvider = cookieProvider
        this.logger = logger
        this.webFetcher = webFetcher
        this.webFetcherScroll = webFetcherScroll
        if (!bootstrapped) {
            PlatformRegistry.registerAll(defaultParsers())
            ProfileRegistry.registerAll(defaultProfileParsers())
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

    /**
     * 主页解析器（与作品页解析器平行的一套 SPI）。
     * 新增平台主页支持：实现 [com.clipdown.parser.spi.ProfileParser] 并加到这里即可。
     */
    fun defaultProfileParsers(): List<ProfileParser> = listOf(
        XiaohongshuProfileParser(),
        XProfileParser(),
        InstagramProfileParser()
    )

    private fun context(): ParseContext = ParseContext(config, http, cookieProvider, logger, webFetcher, webFetcherScroll)

    /** 从剪贴板文本中解析：先抽链接，再走完整链路 */
    fun parseText(text: String?): ParseResult {
        val url = UrlUtil.normalize(
            UrlUtil.extractFirst(text) ?: throw ParseException("剪贴板中没有可识别的链接", retryable = false)
        )
        return parse(url)
    }

    /**
     * 链接形态（主页 / 作品页）：UI 据此决定是打开主页还是走单篇解析。
     *
     * **注意：短链需要网络展开**（`xhslink.cn/o/xxx` 这类看不出是主页还是笔记），
     * 因此本方法可能阻塞，必须在 IO 线程调用；只要本地判定的场景请直接用
     * [ProfileUrls.kindOf]（不展开短链）。
     */
    fun linkKind(rawUrl: String): LinkKind {
        val normalized = UrlUtil.normalize(rawUrl)
        var url = normalized
        var expandedNote = "-"
        if (UrlUtil.isShortLink(url)) {
            runCatching { expand(url) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    url = UrlUtil.normalize(it)
                    expandedNote = url
                }
        }
        val kind = ProfileUrls.kindOf(url)
        logger?.invoke(
            "ParserEngine",
            "linkKind: kind=$kind platform=${UrlUtil.detectPlatform(url)} short=${UrlUtil.isShortLink(normalized)} " +
                "url=$url expandedFrom=$expandedNote"
        )
        return kind
    }

    /**
     * 主页解析：与单篇作品解析**平行的独立入口**。
     *
     * 刻意不复用 [parse] 的降级链——主页是集合形态，用作品页的降级链
     * （远端兜底 / 通用网页解析）只会抓回一页垃圾。非主页链接直接抛明确异常。
     */
    fun parseProfile(rawUrl: String, pages: Int = 1): ProfileResult {
        var url = UrlUtil.normalize(rawUrl)
        if (UrlUtil.isShortLink(url)) {
            runCatching { expand(url) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { url = UrlUtil.normalize(it) }
        }
        val match = ProfileUrls.match(url)
            ?: throw ParseException("这不是博主主页链接：$url", retryable = false)
        if (!config.isEnabled(match.platform)) {
            throw ParseException("${match.platform.displayName} 未开启监听", match.platform, retryable = false)
        }
        val parser = ProfileRegistry.resolveForUrl(url, match.platform)
            ?: throw ParseException(
                "${match.platform.displayName} 的主页解析暂未支持",
                match.platform,
                retryable = false
            )
        return parser.parseProfile(url, match.handle, context(), pages)
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

        // 4) 通用网页兜底（抓 og:video / og:image）——仅对没有专属解析器的链接；
        // 专属平台（IG 等）失败时兜底只会抓回站点的 UI 图标/默认图（垃圾结果，修复 11 配套）
        if (platform == Platform.GENERIC) {
            val generic = runCatching { GenericParser().parse(url, context()) }.getOrNull()
            if (generic != null && !generic.isEmpty) {
                return generic.copy(warning = generic.warning ?: "按通用网页解析，清晰度可能受限")
            }
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
                if (!final.isNullOrBlank() && final != current) {
                    // 平台常把"未登录访问"跳到 /login?redirectPath=<真实地址>：
                    // 直接返回 final 会把登录页当成目标（实测短链展开因此把主页链接判成了笔记页）
                    loginRedirectTarget(final)?.let { return UrlUtil.normalize(it) }
                    return final
                }
                // 小红书 xhslink 等短链常返回 200 的中转页（meta refresh / JS 跳转 / 纯 <a href>）
                // 而不是 3xx，OkHttp 不会跟随，此时必须从页面里把真实地址抠出来
                val fromHtml = redirectFromHtml(getResp?.body, current)
                if (!fromHtml.isNullOrBlank()) return UrlUtil.normalize(fromHtml)
                return current
            }
            current = if (location.startsWith("http")) location else UrlUtil.normalize(location)
        }
        return current
    }

    /**
     * 登录跳转还原：`/login?redirectPath=<真实地址>` → 真实地址。
     *
     * 未登录时平台（小红书等）会把作品页/主页跳到登录页，并把原地址放在 `redirectPath` 里。
     * 展开短链或判断链接形态时若直接采用最终 URL，就会把"登录页"当成目标。
     */
    internal fun loginRedirectTarget(url: String): String? {
        if (!url.contains("/login")) return null
        val m = Regex("""[?&]redirectPath=([^&]+)""").find(url) ?: return null
        val decoded = UrlUtil.decode(m.groupValues[1])
        return decoded.takeIf { it.startsWith("http") }
    }

    /** 从中转页里提取跳转目标，按可靠性依次尝试：
     * 1. `meta refresh`（content="0;url=..."）
     * 2. `location.href=` / `location.replace(...)`
     * 3. 页面里第一个指向**其它已知平台域名**的绝对链接——xhslink.cn 的短链页就是
     *    一张纯 `<a href="https://www.xiaohongshu.com/discovery/item/...?xsec_token=...">`，
     *    既没有 3xx 也没有 meta/JS 跳转（2026-10 用户实测链接取证）。
     *
     * @param excludeHost 当前短链的 host，避免把指向自己的链接当跳转目标
     */
    internal fun redirectFromHtml(body: String?, excludeHost: String = ""): String? {
        if (body.isNullOrBlank()) return null
        Regex(
            """<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]*?content\s*=\s*["'][^"']*?url\s*=\s*([^"'\s>]+)""",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.getOrNull(1)
            ?.let { return UrlUtil.decode(it.trim().trim('\'')) }
        Regex("""location(?:\.href)?\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.let { return it }
        Regex("""location\.replace\(\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.let { return it }
        // 纯 <a href> 中转页：只认落在已知平台域名上的链接，避免把 CDN / 脚本地址当目标
        val skip = UrlUtil.hostOf(excludeHost)
        Regex("""(https?://[A-Za-z0-9\-._~%:]+(?:/[^\s"'<>\\]*)?)""")
            .findAll(body)
            .map { it.groupValues[1] }
            .firstOrNull { url ->
                val h = UrlUtil.hostOf(url)
                h.isNotBlank() && h != skip && UrlUtil.detectPlatform(url) != null
            }?.let { return it.replace("&amp;", "&") }   // HTML 属性里的 &amp; 要还原，否则后续 query 参数名被写坏
        return null
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

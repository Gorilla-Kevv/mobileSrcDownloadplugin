package com.clipdown.parser.config

import com.clipdown.parser.model.Platform

/**
 * 解析内核配置。由上层（Android 设置页）注入，解析内核本身不读任何平台原生 API，
 * 从而保证内核可在纯 JVM 环境下做单元测试。
 *
 * @property remoteEndpoint 远端兜底解析服务的 BaseURL，例如 https://api.cobalt.tools
 * @property remoteEnabled 是否启用远端兜底
 * @property remoteToken 自建服务的鉴权 Key（Authorization: Api-Key <token>）
 * @property enabledPlatforms 用户勾选的监听范围；为空表示全部启用
 * @property connectTimeoutMs 单次解析的连接超时
 * @property readTimeoutMs 单次解析的读取超时
 * @property maxRedirect 短链展开最大跳数
 * @property userAgents 各场景 UA，用于绕过基础的 UA 校验
 */
data class ParserConfig(
    val remoteEndpoint: String = "https://api.cobalt.tools",
    val remoteEnabled: Boolean = false,
    val remoteToken: String = "",
    val enabledPlatforms: Set<String> = emptySet(),
    val connectTimeoutMs: Long = 10_000,
    val readTimeoutMs: Long = 15_000,
    val maxRedirect: Int = 5,
    val userAgents: UserAgents = UserAgents()
) {
    fun isEnabled(platform: Platform): Boolean =
        enabledPlatforms.isEmpty() || enabledPlatforms.contains(platform.id)

    data class UserAgents(
        /** 桌面浏览器：用于抓取带 SSR 数据的网页 */
        val desktop: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        /** 移动端浏览器：用于触发移动版接口（如 m.facebook.com、m.weibo.cn） */
        val mobile: String =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
        /** 社交平台内置爬虫 UA：部分平台对爬虫 UA 直接返回带 media 的精简 DOM */
        val bot: String = "facebookexternalhit/1.1",
        val googleBot: String =
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    )

    companion object {
        fun default() = ParserConfig()
    }
}

package com.clipdown.parser.model

/**
 * 受支持的平台枚举。
 *
 * 每个平台声明了自己的识别特征（域名、路径特征、短链域名），
 * 解析内核据此把一条 URL 路由到对应的 [com.clipdown.parser.spi.PlatformParser]。
 *
 * @property id 稳定标识符，用于设置项持久化（不可随意变更）
 * @property displayName 展示名
 * @property hosts 主域名（含子域），匹配时按后缀比较
 * @property shortHosts 该平台对应的短链域名，命中后需先展开再识别
 * @property pathHints 路径特征，用于在非主域名下辅助判定（可为空）
 * @property loginRequired 是否强依赖登录态 Cookie（影响降级策略与用户提示）
 */
enum class Platform(
    val id: String,
    val displayName: String,
    val hosts: List<String>,
    val shortHosts: List<String> = emptyList(),
    val pathHints: List<String> = emptyList(),
    val loginRequired: Boolean = false
) {
    INSTAGRAM(
        id = "instagram",
        displayName = "Instagram",
        hosts = listOf("instagram.com", "www.instagram.com", "instagr.am"),
        shortHosts = listOf("instagr.am"),
        pathHints = listOf("/p/", "/reel/", "/reels/", "/stories/", "/tv/"),
        loginRequired = true
    ),
    X(
        id = "x",
        displayName = "X (Twitter)",
        hosts = listOf("x.com", "twitter.com", "www.x.com", "www.twitter.com", "mobile.twitter.com"),
        shortHosts = listOf("t.co"),
        pathHints = listOf("/status/", "/i/status/")
    ),
    XIAOHONGSHU(
        id = "xiaohongshu",
        displayName = "小红书",
        // xhslink.cn 是 App 分享文案里实际下发的短链域名（2026-10 实测：xhslink.cn/o/xxx），
        // 漏掉它会导致整条链接被判为"暂不支持"
        hosts = listOf("xiaohongshu.com", "www.xiaohongshu.com", "xhslink.com", "xhslink.cn"),
        shortHosts = listOf("xhslink.com", "xhslink.cn"),
        pathHints = listOf("/explore/", "/discovery/item/", "/user/profile/"),
        loginRequired = true
    ),
    FACEBOOK(
        id = "facebook",
        displayName = "Facebook",
        hosts = listOf("facebook.com", "www.facebook.com", "m.facebook.com", "web.facebook.com", "fb.watch"),
        shortHosts = listOf("fb.watch", "facebook.com/share"),
        pathHints = listOf("/watch", "/reel", "/videos/", "/photo", "/permalink.php", "/share/v/", "/share/r/"),
        loginRequired = true
    ),
    TIKTOK(
        id = "tiktok",
        displayName = "TikTok",
        hosts = listOf("tiktok.com", "www.tiktok.com", "vm.tiktok.com", "vt.tiktok.com"),
        shortHosts = listOf("vm.tiktok.com", "vt.tiktok.com"),
        pathHints = listOf("/video/", "/photo/")
    ),
    DOUYIN(
        id = "douyin",
        displayName = "抖音",
        hosts = listOf("douyin.com", "www.douyin.com", "v.douyin.com", "iesdouyin.com"),
        shortHosts = listOf("v.douyin.com"),
        pathHints = listOf("/video/", "/note/", "/slides/")
    ),
    BILIBILI(
        id = "bilibili",
        displayName = "哔哩哔哩",
        hosts = listOf("bilibili.com", "www.bilibili.com", "m.bilibili.com", "b23.tv"),
        shortHosts = listOf("b23.tv"),
        pathHints = listOf("/video/", "/bangumi/", "/list/")
    ),
    WEIBO(
        id = "weibo",
        displayName = "微博",
        hosts = listOf("weibo.com", "www.weibo.com", "m.weibo.cn", "weibo.cn", "t.cn"),
        shortHosts = listOf("t.cn"),
        pathHints = listOf("/detail/", "/status/", "/show?fid=")
    ),
    YOUTUBE(
        id = "youtube",
        displayName = "YouTube",
        hosts = listOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"),
        shortHosts = listOf("youtu.be"),
        pathHints = listOf("/watch", "/shorts/", "/live/")
    ),
    GENERIC(
        id = "generic",
        displayName = "通用网页",
        hosts = emptyList(),
        pathHints = emptyList()
    );

    companion object {
        fun byId(id: String): Platform? = entries.firstOrNull { it.id == id }

        /** 依据 host 精确或后缀匹配平台；返回 null 表示不在受支持范围内 */
        fun matchHost(host: String): Platform? {
            val h = host.lowercase().trim('.')
            if (h.isBlank()) return null
            for (p in entries) {
                if (p == GENERIC) continue
                val all = p.hosts + p.shortHosts
                if (all.any { h == it || h.endsWith(".$it") }) return p
            }
            return null
        }
    }
}

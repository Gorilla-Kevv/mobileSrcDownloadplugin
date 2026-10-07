package com.clipdown.parser.spi

import com.clipdown.parser.model.Platform
import com.clipdown.parser.model.ProfileResult

/**
 * 博主主页解析器（可插拔 SPI）。
 *
 * 与单篇作品解析器 [PlatformParser] 平行、互不干扰：
 * 主页是"一个作者 + N 篇笔记"的集合形态，一次请求只拿列表，
 * **不在这一层展开每篇的媒体资源**（否则要抓 N 个页面）；
 * 用户点开某篇时再走 [PlatformParser] 的单篇链路取原图/原视频。
 *
 * 新增平台主页支持 = 新增一个实现类并注册进
 * [com.clipdown.parser.core.ProfileRegistry]，路由与 UI 层无需改动。
 */
interface ProfileParser {

    val platform: Platform

    /** 解析器标识，写入 [ProfileResult.resolverId] */
    val id: String

    /** 细粒度判断（已由路由层做过平台判定，默认全部接管） */
    fun canHandleProfile(url: String): Boolean = true

    /**
     * 解析主页。
     *
     * @param url 已归一化的主页链接
     * @param handle 路由层抽出的用户名/用户 ID（各平台语义不同，如 IG 用户名、B 站 mid）
     * @param ctx 解析上下文（HTTP / Cookie / WebView 能力）
     * @return 主页信息 + 笔记列表；拿不到时抛 [com.clipdown.parser.model.ParseException]
     */
    /**
     * 解析主页。
     *
     * @param pages 需要加载的页数（1 = 仅首屏）。>1 时由实现方自行决定是否支持继续加载，
     *              不支持时按首屏结果返回即可（[ProfileResult.hasMore] 会告知 UI 还能否继续）。
     */
    fun parseProfile(url: String, handle: String, ctx: ParseContext, pages: Int = 1): ProfileResult
}

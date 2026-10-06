package com.clipdown.parser.core

import com.clipdown.parser.model.Platform
import com.clipdown.parser.spi.ProfileParser

/**
 * 主页解析器注册表（可插拔 SPI）。
 *
 * 与 [PlatformRegistry] 分离：作品页解析器与主页解析器是两套独立的降级链，
 * 互不干扰（主页解析失败不应回落到"通用网页解析"抓封面）。
 */
object ProfileRegistry {

    private val parsers = mutableListOf<ProfileParser>()

    fun register(parser: ProfileParser) {
        synchronized(parsers) {
            parsers.removeAll { it.id == parser.id }
            parsers.add(parser)
        }
    }

    fun registerAll(list: List<ProfileParser>) = list.forEach(::register)

    /** 按平台找主页解析器；未注册该平台时返回 null（由上层给出"暂不支持"的明确提示） */
    fun resolve(platform: Platform): ProfileParser? =
        parsers.firstOrNull { it.platform == platform }

    fun resolveForUrl(url: String, platform: Platform): ProfileParser? =
        parsers.filter { it.platform == platform }.firstOrNull { it.canHandleProfile(url) }
            ?: resolve(platform)

    /** 已支持主页解析的平台（设置页/提示文案可据此展示） */
    fun supportedPlatforms(): List<Platform> = parsers.map { it.platform }.distinct()

    fun all(): List<ProfileParser> = parsers.toList()

    fun clear() = synchronized(parsers) { parsers.clear() }
}

package com.clipdown.parser.core

import com.clipdown.parser.model.Platform
import com.clipdown.parser.spi.PlatformParser

/**
 * 解析器注册表（可插拔 SPI）。
 *
 * 解析内核启动时按优先级注册所有内置解析器；
 * 后续扩展新平台只需要新增实现类并在此注册，不需要改动路由与 UI 层。
 */
object PlatformRegistry {

    private val parsers = mutableListOf<PlatformParser>()

    fun register(parser: PlatformParser) {
        synchronized(parsers) {
            parsers.removeAll { it.id == parser.id }
            parsers.add(parser)
            parsers.sortByDescending { if (it.platform == Platform.GENERIC) 0 else 1 }
        }
    }

    fun registerAll(list: List<PlatformParser>) = list.forEach(::register)

    /** 依据平台找到解析器；找不到时回退到通用解析器 */
    fun resolve(platform: Platform): PlatformParser? =
        parsers.firstOrNull { it.platform == platform && it.canHandle("*") }
            ?: parsers.firstOrNull { it.platform == platform }
            ?: parsers.firstOrNull { it.platform == Platform.GENERIC }

    fun resolveForUrl(url: String, platform: Platform): PlatformParser? =
        parsers.filter { it.platform == platform }.firstOrNull { it.canHandle(url) }
            ?: resolve(platform)

    fun all(): List<PlatformParser> = parsers.toList()

    fun clear() = synchronized(parsers) { parsers.clear() }
}

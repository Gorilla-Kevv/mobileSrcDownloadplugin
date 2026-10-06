package com.clipdown.parser.parsers

/**
 * 网页解析通用工具。
 *
 * 各平台的 SSR 数据结构变动频繁，这里统一用"宽松正则 + JSON 导航"的组合策略，
 * 而不是严格依赖某一套 DOM 结构，以提高对页面改版的容错性。
 */
object HtmlUtil {

    /**
     * 提取 meta 内容。同时兼容 `content` 在 `property` 之前或之后的两种写法。
     */
    fun meta(html: String, property: String): String? {
        val p = property.replace(Regex("""[.*+?^${'$'}{}()|\[\]\\]""")) { "\\${it.value}" }
        val tag = Regex(
            """<meta[^>]*?(?:property|name|itemprop)\s*=\s*["']${p}["'][^>]*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value
            ?: Regex(
                """<meta[^>]*?content\s*=\s*["']([^"']*)["'][^>]*?(?:property|name|itemprop)\s*=\s*["']${p}["'][^>]*>""",
                RegexOption.IGNORE_CASE
            ).find(html)?.value
            ?: return null
        return Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.getOrNull(1)?.unescapeHtml()
    }

    fun title(html: String): String? =
        Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.unescapeHtml()?.trim()?.takeIf { it.isNotBlank() }
            ?: meta(html, "og:title")

    /** 抓取页面上所有 mp4/m3u8 直链（video 标签、source 标签、JSON 字段兜底） */
    fun findVideoSources(html: String): List<String> {
        val out = linkedSetOf<String>()
        Regex("""<video[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.groupValues[1].unescapeHtml()) }
        Regex("""<source[^>]+src\s*=\s*["']([^"']+\.(?:mp4|m3u8|webm|mov)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.groupValues[1].unescapeHtml()) }
        Regex("""["'](https?:[^"']+?\.(?:mp4|m3u8)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.groupValues[1].unescapeHtml()) }
        return out.filter { it.startsWith("http") }
    }

    /** 抓取页面上所有图片直链（用于图集兜底） */
    fun findImages(html: String, limit: Int = 20): List<String> {
        val out = linkedSetOf<String>()
        Regex("""["'](https?:[^"']+?\.(?:jpg|jpeg|png|webp)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { out.add(it.groupValues[1].unescapeHtml()) }
        return out.filter { it.startsWith("http") }.take(limit)
    }

    /**
     * 抽取页面内嵌 JSON 变量。
     * 支持 `window.__INITIAL_STATE__ = {...}`、`var $render_data = [...]` 等常见写法，
     * 通过括号配对来截取完整的 JSON 片段，避免简单正则截断。
     */
    fun inlineJson(html: String, varName: String): String? {
        val escaped = Regex.escape(varName)
        val start = Regex("""${escaped}\s*=\s*""").find(html) ?: return null
        var i = start.range.last + 1
        while (i < html.length && html[i].isWhitespace()) i++
        if (i >= html.length) return null
        val open = html[i]
        val close = when (open) {
            '{' -> '}'
            '[' -> ']'
            else -> return null
        }
        var depth = 0
        var inStr = false
        var escapedChar = false
        val end = i
        for (p in i until html.length) {
            val c = html[p]
            when {
                escapedChar -> escapedChar = false
                c == '\\' && inStr -> escapedChar = true
                c == '"' -> inStr = !inStr
                !inStr && c == open -> depth++
                !inStr && c == close -> {
                    depth--
                    if (depth == 0) return html.substring(end, p + 1)
                }
            }
        }
        return null
    }

    /**
     * 抓取形如 "key":"value" 的 JSON 字段（含转义写法 \"key\":\"value\"，值中允许 \uXXXX 与 \/ 等转义序列）。
     *
     * 分两段匹配，**先带引号、再无引号**：
     * 1. 带引号的值：惰性 `[^"\\]|\\.` + 要求值后紧跟分隔符（`,`/`}`/`]` 或文本结尾）。
     *    `"`/`\` 被排除在字符类外，`\"` 这类转义引号由 `\\.` 吃掉，不会被误判为值结尾；
     *    后瞻分隔符保证不会提前收尾——曾因"结尾引号可选 + 惰性"把含引号的标题截断成前半段。
     * 2. 无引号的值（数字/布尔/null，如 SSR 的 `"posted":4127`）：**必须单独一段**。
     *    否则第 1 段的可选结尾引号会吃掉下一个键的开引号，后瞻随即失败，整条匹配作废——
     *    实测 `"posted":4127,"liked":...` 取不到值。
     */
    fun jsonField(text: String, key: String): List<String> {
        val escaped = Regex.escape(key)
        val quoted = Regex("""\\?"${escaped}\\?"\s*:\s*\\?"((?:[^"\\]|\\.)*?)\\?"\s*(?=[,}\]]|$)""")
        val hits = quoted.findAll(text).map { it.groupValues[1] }.toList()
        if (hits.isNotEmpty()) return hits.map { it.unescapeJson() }

        val bare = Regex("""\\?"${escaped}\\?"\s*:\s*([^,}\]\s"]+)\s*(?=[,}\]]|$)""")
        return bare.findAll(text).map { it.groupValues[1] }.toList()
    }

    /** 普通函数形态的 HTML 反转义，便于跨类调用（避免成员扩展函数的调用歧义） */
    fun unescapeHtmlOf(s: String): String = s.unescapeHtml()

    fun unescapeJsonOf(s: String): String = s.unescapeJson()

    fun String.unescapeHtml(): String = this
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("\\/", "/")

    /**
     * JSON 字符串反转义。
     *
     * IG 页面是 JSON-in-JS 双重编码：`\/` 在源码里写作 `\\/`、`%` 写作 `\\u0025`。
     * 先把字面反斜杠序列 `\\` 还原为 `\`（相当于 JS 层解码，把双重形态降为单层），
     * 再做常规 JSON 解码（\uXXXX / \/）即可一次还原干净——
     * 修复 9：此前只做单层，video_versions 的 URL 带着 `\\/` 字面反斜杠进了下载器（403 根因，
     * 图片走 kotlinx 标准解码不受影响所以一直正常）。
     */
    fun String.unescapeJson(): String = this
        .replace("\\\\", "\\")
        .replace(Regex("""\\u([0-9a-fA-F]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }
        .replace("\\/", "/")
        // 常规 JSON 字符串转义：不还原会让标题/正文里带出字面的 \" \n（曾导致标题显示为"…坦承\"…"）
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\b", "\b")
        .replace("\\f", "\u000C")
        .replace("\\\"", "\"")
        .replace("&amp;", "&")
}

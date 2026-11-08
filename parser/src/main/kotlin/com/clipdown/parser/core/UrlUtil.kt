package com.clipdown.parser.core

import com.clipdown.parser.model.Platform
import java.net.IDN
import java.net.URI
import java.net.URLDecoder

/**
 * URL 提取与归一化工具。
 *
 * 剪贴板里的文本形态非常多样（中文文案 + 短链 + emoji + 无协议头），
 * 因此这里不追求"标准 URL 解析"，而是先做宽松召回，再做归一化收敛。
 */
object UrlUtil {

    /**
     * 带协议头的链接，含无协议的 www 形式。
     * 尾部刻意排除中文标点与常见收尾符号，避免把文案一起吃进来。
     */
    private val URL_PATTERN =
        Regex("""(?:https?://|www\.)[A-Za-z0-9\-._~%]+(?::\d+)?(?:/[^\s"'<>“”‘’，,。；;！!？）)\]】]*)?""")

    /** 无协议头的裸域名，如 xhslink.com/a/xxx、v.douyin.com/xxx */
    private val BARE_HOST_PATTERN =
        Regex("""(?<![A-Za-z0-9.\-])((?:[a-z0-9](?:[a-z0-9\-]*[a-z0-9])?\.)+(?:com|cn|net|org|io|tv|co|me|link|watch|be|tv|app)(?:/[^\s"'<>“”‘’，,。；;！!？）)\]】]*)?)""")

    private val TRACKING_PARAMS = setOf(
        "igshid", "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "ref", "ref_src", "ref_url", "s", "t", "si", "feature", "src", "share_id",
        "share_from", "share_medium", "share_plat", "share_source", "share_tag", "share_times",
        "spm_id_from", "from_spmid", "vd_source", "unique_k", "_r", "chksm", "scene",
        "entry", "source", "apptime", "gid", "tt_from", "iid", "region", "mid", "ug",
        "xsec_source", "share_token", "b_id", "from_source", "share_type"
    )

    /**
     * 「会轮换」的鉴权/签名参数：**解析链路必须保留**（没有 xsec_token 小红书直接拒绝），
     * 但**去重链路必须忽略**——同一条笔记被不同人分享、或隔几天再复制一次，
     * 这些值都会变，带着它们比对会让「已解析过」的提醒永远匹配不上。
     */
    private val VOLATILE_PARAMS = setOf(
        "xsec_token", "xsec_source", "share_token", "sign", "sig", "signature",
        "expires", "expire", "ts", "timestamp", "auth_key", "token", "nonce"
    )

    /** 小红书笔记 ID 提取：/explore/<id>、/discovery/item/<id>、/notes/<id> */
    private val XHS_NOTE_ID = Regex(
        """/(?:explore|discovery/item|notes)/([0-9a-fA-F]{16,32})"""
    )

    /** 从任意文本中召回第一个候选链接 */
    fun extractFirst(text: String?): String? = extractAll(text).firstOrNull()

    /** 从任意文本中召回所有候选链接（保持出现顺序，去重） */
    fun extractAll(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val raw = preprocess(text)
        val found = linkedSetOf<String>()
        URL_PATTERN.findAll(raw).map { it.value.trimEnd('.', ',', ';') }.forEach { found.add(it) }
        BARE_HOST_PATTERN.findAll(raw).map { it.value.trimEnd('.', ',', ';') }.forEach { found.add("https://$it") }
        return found.filter { it.length > 12 }
    }

    /**
     * 文本预处理：还原被转义的字符、剥离 emoji 与零宽字符。
     * 小红书/抖音的分享文案常把链接包裹在表情之间，直接跑正则容易截断。
     */
    private fun preprocess(text: String): String = text
        .replace(' ', ' ')
        .replace('​', ' ')
        .replace('﻿', ' ')
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\n", " ")
        .replace("\r", " ")

    /** 归一化：补协议头、Punycode、剔除跟踪参数、统一末尾斜杠 */
    fun normalize(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.startsWith("//")) s = "https:$s"
        if (!s.contains("://")) s = "https://$s"
        val uri = runCatching { URI(s) }.getOrNull() ?: return s
        val host = runCatching { IDN.toASCII(uri.host ?: return s) }.getOrNull() ?: uri.host ?: return s
        // X 的 /mediaViewer 是同一推文的媒体查看视图：剥掉后与作品页共用识别记忆，
        // 避免"播放视频→地址栏变体→绕过去重反复弹窗"
        val path = (uri.path ?: "").replace(Regex("/mediaViewer/?$", RegexOption.IGNORE_CASE), "")
        val kept = (uri.query ?: "").split('&').filter { q ->
            val name = q.substringBefore('=')
            name.isNotEmpty() && name !in TRACKING_PARAMS
        }
        val query = if (kept.isEmpty()) "" else kept.joinToString("&").let { "?$it" }
        return "${uri.scheme}://$host${if (uri.port != -1) ":${uri.port}" else ""}${path.trimEnd('/')}$query"
    }

    /**
     * 去重键（dedup key）：用于「已解析过该链接」的长期比对公司判定。
     *
     * 与 [normalize] 的区别：
     * - [normalize] 面向**请求**，必须保留 `xsec_token` 等鉴权参数，否则小红书直接拒绝；
     * - 本函数面向**记忆**，必须剥掉所有会随分享轮换的鉴权/签名参数，否则同一条笔记
     *   隔几天再复制一次时 token 已变，比对必然失败 —— 这正是「几天前解析过的链接
     *   再解析一遍不弹『已解析』提示」的根因。
     *
     * 小红书额外收敛到「笔记 ID」：/explore/<id>、/discovery/item/<id>、/notes/<id>
     * 三个路径形态指向同一篇笔记，统一成 `xhs:note:<id>`。
     */
    fun dedupKey(raw: String): String {
        val base = stripVolatile(normalize(raw))
        val noteId = XHS_NOTE_ID.find(pathOf(base))?.groupValues?.getOrNull(1)
        return if (noteId != null) "xhs:note:${noteId.lowercase()}" else base
    }

    /** 剥掉 query 中所有会轮换的鉴权/签名参数（保留其余业务参数并稳定排序） */
    private fun stripVolatile(normalized: String): String {
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return normalized
        val kept = (uri.query ?: "").split('&')
            .filter { q ->
                val name = q.substringBefore('=')
                name.isNotEmpty() && name !in VOLATILE_PARAMS && name !in TRACKING_PARAMS
            }
            .sorted()
        val query = if (kept.isEmpty()) "" else kept.joinToString("&").let { "?$it" }
        val host = uri.host ?: return normalized
        return "${uri.scheme}://$host${if (uri.port != -1) ":${uri.port}" else ""}${(uri.path ?: "").trimEnd('/')}$query"
    }

    fun hostOf(url: String): String =
        runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")

    fun pathOf(url: String): String =
        runCatching { URI(url).path.orEmpty() }.getOrDefault("")

    /** 匹配平台：先看 host，再看路径特征 */
    fun detectPlatform(url: String): Platform? {
        val host = hostOf(url)
        val byHost = Platform.matchHost(host)
        if (byHost != null) return byHost
        val path = pathOf(url)
        return Platform.entries.firstOrNull { p ->
            p != Platform.GENERIC && p.pathHints.any { path.contains(it) }
        }
    }

    fun isShortLink(url: String): Boolean {
        val p = detectPlatform(url) ?: return false
        val host = hostOf(url)
        return p.shortHosts.any { host == it || host.endsWith(".$it") }
    }

    /** 百分号解码（用于从重定向 URL 中还原真实链接） */
    fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** 生成文件名友好的标题 */
    fun sanitizeFileName(name: String, fallback: String = "clipdown"): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.')
        return if (cleaned.length <= 1) fallback else cleaned.take(60)
    }
}

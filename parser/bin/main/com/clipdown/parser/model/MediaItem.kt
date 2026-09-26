package com.clipdown.parser.model

import kotlinx.serialization.Serializable

/** 媒体形态 */
@Serializable
enum class MediaKind { VIDEO, IMAGE, AUDIO, GALLERY, UNKNOWN }

/** 单条可下载资源。下载器只依赖这一层结构，不感知上游平台差异 */
@Serializable
data class MediaItem(
    /** 同一次解析内的稳定标识，建议使用 URL 摘要 */
    val id: String,
    /** 直链（或 m3u8 / mpd 列表地址） */
    val url: String,
    val kind: MediaKind = MediaKind.UNKNOWN,
    /** 清晰度标签，如 "1080p"、"原图"、"Audio 128k" */
    val quality: String = "默认",
    /** 排序权重，越大越优先推荐 */
    val rank: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val bitrateKbps: Int? = null,
    val sizeBytes: Long? = null,
    val durationMs: Long? = null,
    val mimeType: String? = null,
    /** 容器后缀：mp4 / mov / jpg / webp / m3u8 / mpd */
    val container: String? = null,
    /** 下载时必须携带的请求头（Referer、Cookie 等） */
    val headers: Map<String, String> = emptyMap(),
    /** 是否为流媒体清单（m3u8/mpd），下载器需要走分片合并流程 */
    val isPlaylist: Boolean = false,
    /** 分离音轨（DASH 场景），下载后需与视频合并 */
    val audioUrl: String? = null,
    val audioHeaders: Map<String, String>? = null,
    val subtitleUrl: String? = null,
    /** 需要重新封装（ts 合并为 mp4） */
    val needsRemux: Boolean = false,
    val fileNameHint: String? = null
) {
    /** 供 UI 展示的简短副标题，例如 "1080p · 12.4 MB · 1920×1080" */
    fun subtitle(): String = buildList {
        add(quality)
        sizeBytes?.let { add(formatSize(it)) }
        if (width != null && height != null) add("${width}×${height}")
        durationMs?.let { if (it > 0) add(formatDuration(it)) }
    }.joinToString(" · ")

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "--"
            val units = arrayOf("B", "KB", "MB", "GB")
            var v = bytes.toDouble()
            var i = 0
            while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
            return if (i == 0) "$bytes B" else String.format("%.1f %s", v, units[i])
        }

        fun formatDuration(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
        }
    }
}

/** 解析产物：一次解析的完整结果 */
@Serializable
data class ParseResult(
    val platform: Platform,
    /** 命中的解析器 ID，便于排查与埋点 */
    val resolverId: String,
    val sourceUrl: String,
    val title: String? = null,
    val author: String? = null,
    val coverUrl: String? = null,
    val coverHeaders: Map<String, String>? = null,
    val description: String? = null,
    val media: List<MediaItem> = emptyList(),
    /** 解析来源：本地直连 / 公开接口 / 远端服务 / 内置浏览器嗅探 */
    val source: ParseSource = ParseSource.LOCAL,
    val parsedAtMillis: Long = System.currentTimeMillis(),
    /** 业务级失败原因（网络正常但拿不到资源，如需要登录） */
    val warning: String? = null
) {
    val isEmpty: Boolean get() = media.isEmpty()
}

@Serializable
enum class ParseSource {
    /** 应用内直接请求平台接口解析 */
    LOCAL,

    /** 平台官方公开接口（oEmbed / syndication 等） */
    OFFICIAL,

    /** 用户自建或公共的远端解析服务（cobalt / yt-dlp 服务端） */
    REMOTE,

    /** 内置 WebView 嗅探：拦截页面中的媒体请求 */
    SNIFFER
}

/** 解析失败：区分"不可解析"与"可重试" */
class ParseException(
    message: String,
    val platform: Platform = Platform.GENERIC,
    val retryable: Boolean = true,
    cause: Throwable? = null
) : RuntimeException(message, cause)

package com.clipdown.parser.stream

/**
 * HLS（m3u8）播放列表模型。
 *
 * 解析内核只负责"读懂"播放列表，真正的并发下载与合并由下载器模块完成，
 * 这样同一份列表结构既能给下载器用，也能给 UI 做清晰度选择。
 */

data class M3u8Key(
    val method: String,
    val uri: String?,
    val ivHex: String?,
    val keyFormat: String? = null
) {
    val isAes128: Boolean get() = method.equals("AES-128", ignoreCase = true)
}

data class M3u8Segment(
    val uri: String,
    val durationSec: Double,
    val key: M3u8Key? = null,
    val byteRange: Pair<Long, Long>? = null,
    val discontinuity: Boolean = false
)

/** 多码率主列表中的一档 */
data class M3u8Variant(
    val url: String,
    val bandwidth: Int,
    val resolution: String?,
    val codecs: String?,
    val audioGroup: String? = null,
    val frameRate: Double? = null
) {
    val height: Int get() = resolution?.substringAfter('x')?.toIntOrNull()
        ?: resolution?.substringBefore('x')?.toIntOrNull() ?: 0

    fun label(): String = resolution?.let { "${it.substringAfter('x')}p" }
        ?: "${bandwidth / 1000}k"
}

data class M3u8Playlist(
    val baseUrl: String,
    val isMaster: Boolean,
    val variants: List<M3u8Variant> = emptyList(),
    val segments: List<M3u8Segment> = emptyList(),
    val targetDuration: Int = 0,
    val initSegmentUri: String? = null,
    val endList: Boolean = false,
    val version: Int = 0
) {
    val totalDurationSec: Double get() = segments.sumOf { it.durationSec }
}

/**
 * m3u8 解析实现。
 *
 * 覆盖：主列表（EXT-X-STREAM-INF）、媒体列表（EXTINF / EXT-X-KEY / EXT-X-MAP / EXT-X-BYTERANGE）、
 * 相对地址补全、注释与空行容错。
 */
object M3u8Parser {

    fun parse(content: String, baseUrl: String): M3u8Playlist {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val variants = mutableListOf<M3u8Variant>()
        val segments = mutableListOf<M3u8Segment>()

        var currentKey: M3u8Key? = null
        var pendingDuration = 0.0
        var pendingByteRange: Pair<Long, Long>? = null
        var pendingDiscontinuity = false
        var pendingVariant: MutableMap<String, String>? = null
        var initUri: String? = null
        var targetDuration = 0
        var endList = false
        var version = 0

        for (line in lines) {
            when {
                line.startsWith("#EXTM3U") -> Unit

                line.startsWith("#EXT-X-VERSION:") ->
                    version = line.substringAfter(':').trim().toIntOrNull() ?: 0

                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    targetDuration = line.substringAfter(':').trim().toIntOrNull() ?: 0

                line.startsWith("#EXT-X-ENDLIST") -> endList = true

                line.startsWith("#EXT-X-MAP:") -> {
                    initUri = attr(line.substringAfter(':'), "URI")?.let { resolve(baseUrl, it) }
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = line.substringAfter(':')
                    currentKey = M3u8Key(
                        method = attr(attrs, "METHOD") ?: "NONE",
                        uri = attr(attrs, "URI")?.let { resolve(baseUrl, it) },
                        ivHex = attr(attrs, "IV")?.removePrefix("0x")?.removePrefix("0X"),
                        keyFormat = attr(attrs, "KEYFORMAT")
                    )
                    if (currentKey?.method == "NONE") currentKey = null
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val v = line.substringAfter(':').trim()
                    val len = v.substringBefore('@').toLongOrNull()
                    val off = v.substringAfter('@', "").toLongOrNull()
                    pendingByteRange = if (len != null && off != null) len to off else null
                }

                line.startsWith("#EXT-X-DISCONTINUITY") -> pendingDiscontinuity = true

                line.startsWith("#EXTINF:") ->
                    pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0

                line.startsWith("#EXT-X-STREAM-INF:") ->
                    pendingVariant = parseAttrs(line.substringAfter(':')).toMutableMap()

                line.startsWith("#") -> Unit

                pendingVariant != null -> {
                    val a = pendingVariant!!
                    variants += M3u8Variant(
                        url = resolve(baseUrl, line),
                        bandwidth = a["BANDWIDTH"]?.toIntOrNull() ?: 0,
                        resolution = a["RESOLUTION"],
                        codecs = a["CODECS"],
                        audioGroup = a["AUDIO"],
                        frameRate = a["FRAME-RATE"]?.toDoubleOrNull()
                    )
                    pendingVariant = null
                }

                else -> {
                    segments += M3u8Segment(
                        uri = resolve(baseUrl, line),
                        durationSec = pendingDuration,
                        key = currentKey,
                        byteRange = pendingByteRange,
                        discontinuity = pendingDiscontinuity
                    )
                    pendingDuration = 0.0
                    pendingByteRange = null
                    pendingDiscontinuity = false
                }
            }
        }

        return M3u8Playlist(
            baseUrl = baseUrl,
            isMaster = variants.isNotEmpty(),
            variants = variants.sortedByDescending { it.bandwidth },
            segments = segments,
            targetDuration = targetDuration,
            initSegmentUri = initUri,
            endList = endList,
            version = version
        )
    }

    /** 选择指定档位：index 超界时回退到带宽最高的一档 */
    fun pickVariant(list: M3u8Playlist, index: Int): M3u8Variant? =
        list.variants.getOrNull(index) ?: list.variants.firstOrNull()

    private fun attr(attrs: String, key: String): String? =
        Regex("""${key}=("[^"]*"|[^,]*)""").find(attrs)?.groupValues?.getOrNull(1)?.trim('"')

    private fun parseAttrs(s: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("""([A-Z0-9\-]+)=("[^"]*"|[^,]*)""").findAll(s).forEach {
            out[it.groupValues[1]] = it.groupValues[2].trim('"')
        }
        return out
    }

    fun resolve(base: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        val basePath = base.substringBefore('?')
        return when {
            uri.startsWith("/") -> {
                val m = Regex("""^(https?://[^/]+)""").find(basePath)?.groupValues?.getOrNull(1)
                    ?: return uri
                m + uri
            }
            else -> basePath.substringBeforeLast('/') + "/" + uri
        }
    }
}

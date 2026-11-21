package com.clipdown.parser.parsers

import com.clipdown.parser.model.MediaItem
import com.clipdown.parser.model.MediaKind
import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.ParseResult
import com.clipdown.parser.model.ParseSource
import com.clipdown.parser.model.Platform
import com.clipdown.parser.spi.ParseContext
import com.clipdown.parser.spi.PlatformParser
import com.clipdown.parser.spi.ParserDsl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * X（Twitter）解析器。
 *
 * 主通道是 X 公开的 syndication 接口（无需登录）：
 * `https://cdn.syndication.twimg.com/tweet-result?id={id}&token={token}`
 * 其中 token 由推文 ID 推导：((id / 1e15) * π).toString(36) 去掉 '.' 与 '0'。
 *
 * 该接口返回标准的 `mediaDetails[].video_info.variants[]`（多清晰度 MP4）与 `photos[]`。
 */
class XParser : PlatformParser {

    override val platform: Platform = Platform.X
    override val id: String = "x-syndication-v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val idRegex = Regex("""/status(?:es)?/(\d{5,25})""")

    override fun canHandle(url: String): Boolean = idRegex.containsMatchIn(url)

    override fun parse(url: String, ctx: ParseContext): ParseResult {
        val tweetId = idRegex.find(url)?.groupValues?.getOrNull(1)
            ?: throw ParseException("无法从链接中提取推文 ID", platform, retryable = false)

        val token = guestToken(tweetId)
        val api = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=zh-cn&token=$token"
        val headers = ctx.headersFor(platform) + mapOf(
            "Referer" to "https://platform.twitter.com/",
            "Origin" to "https://platform.twitter.com",
            "Accept" to "application/json"
        )

        val resp = runCatching { ctx.http.get(api, headers) }
            .getOrElse { throw ParseException("网络请求失败：${it.message}", platform) }
        val body = resp.body
            ?: throw ParseException("公开接口返回空数据", platform)
        if (resp.code == 404) {
            // 实测（2026-10）：tweet-result 对已删除/较老的推文返回 404 HTML 页
            throw ParseException("推文不存在、已删除或超出公开接口的覆盖范围", platform, retryable = false)
        }
        if (resp.code >= 400) throw ParseException("公开接口返回 ${resp.code}", platform)

        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw ParseException("接口数据结构异常", platform) }

        val media = mutableListOf<MediaItem>()
        val xHeaders = mapOf("Referer" to "https://x.com/")

        root["mediaDetails"]?.jsonArray?.forEachIndexed { mi, el ->
            val o = el.jsonObject
            // 阶段 86：GIF 的 content_type 可能是 image/gif，也可能是 video/mp4 的转码；
            // 旧实现要求 content_type 含 mp4，会把只给 image/gif 的动图整条丢掉。
            val variants = o["video_info"]?.jsonObject?.get("variants")?.jsonArray
            variants?.mapNotNull { v ->
                val vo = v.jsonObject
                val u = vo.str("url") ?: return@mapNotNull null
                val ct = (vo.str("content_type") ?: "").lowercase()
                val base = u.substringBefore('?').lowercase()
                val isMp4 = ct.contains("mp4") || base.endsWith(".mp4")
                val isGif = ct.contains("gif") || base.endsWith(".gif")
                if (!isMp4 && !isGif) return@mapNotNull null
                Triple(u, vo.int("bitrate") ?: 0, isGif && !isMp4)
            }
                // mp4 优先（相册可直接播），gif 作为备选保留
                ?.sortedWith(
                    compareByDescending<Triple<String, Int, Boolean>> { !it.third }
                        .thenByDescending { it.second }
                )
                ?.forEachIndexed { i, (u, bitrate, isGifOnly) ->
                    media += MediaItem(
                        id = "x-v-$mi-$i",
                        url = u,
                        // GIF 归 IMAGE：写 Pictures/ 相册可播动图；标 VIDEO 会被塞进 Movies/ 打不开
                        kind = if (isGifOnly) MediaKind.IMAGE else MediaKind.VIDEO,
                        quality = if (isGifOnly) "GIF 原图" else bitrateLabel(bitrate, i),
                        // 多个视频时按「第几个视频」分段，避免不同视频的变体 rank 撞车
                        rank = 100 - mi * 10 - i,
                        container = if (isGifOnly) "gif" else "mp4",
                        mimeType = if (isGifOnly) "image/gif" else "video/mp4",
                        headers = xHeaders
                    )
                }
            // 注：mediaDetails[].photo_info 已被上游移除（2026-10 实测为 undefined），
            // 图片统一走下方 photos[] 通道
        }

        root["photos"]?.jsonArray?.forEachIndexed { i, el ->
            val u = el.jsonObject.str("url") ?: el.jsonObject.str("expanded_url") ?: return@forEachIndexed
            val base = u.substringBefore("?format=")
            media += MediaItem(
                id = "x-ph-$i",
                url = "$base?format=jpg&name=orig",
                kind = MediaKind.IMAGE,
                quality = "原图 ${i + 1}",
                rank = 90 - i,
                container = "jpg",
                headers = xHeaders
            )
        }

        // syndication 拿不到媒体时的兜底：第三方公开镜像 fxTwitter（2026-10 实测可用，
        // 它给的是 pbs.twimg.com 直链）。敏感内容标记 / 较老推文 / 已下线媒体都能覆盖
        //
        // 阶段 85 修正：**混合推文（图+视频）里 syndication 常常只下发 photos[]，
        // 不下发 video_info.variants**。旧条件是 `media.isEmpty()`，于是图片一有值
        // 就跳过兜底，视频永远丢失。现在改为「缺哪一类就补哪一类」，并按 URL 去重合并。
        var warning: String? = null
        val hasVideo = media.any { it.kind == MediaKind.VIDEO }
        val hasImage = media.any { it.kind == MediaKind.IMAGE }
        // 推文声明了媒体实体，但某一类没拿到 → 值得再问一次镜像
        val declaredMedia = root["entities"]?.jsonObject?.containsKey("media") == true ||
            root["mediaDetails"] != null
        val needFallback = media.isEmpty() || (declaredMedia && (!hasVideo || !hasImage))

        if (needFallback) {
            val fallback = fetchFxMedia(tweetId, ctx)
            if (fallback.isNotEmpty()) {
                val added = mergeMissingMedia(media, fallback)
                if (added > 0) {
                    warning = if (media.isEmpty()) {
                        "该推文的媒体未随公开 syndication 接口下发（常见于敏感内容标记或较老的推文），" +
                            "已改由第三方镜像 fxTwitter 获取"
                    } else {
                        "公开接口只下发了部分媒体（缺少${if (!hasVideo) "视频" else "图片"}），" +
                            "已由第三方镜像 fxTwitter 补齐 $added 项"
                    }
                    ctx.log(id, "fxTwitter 兜底命中：新增 $added 项，合计 ${media.size} 项")
                }
            }
        }

        if (media.isEmpty()) {
            // 空媒体分诊：敏感内容标记时公开接口会 200 返回但不下发媒体（实测 2026-10），
            // entities.media 仍在（只剩 t.co 短链）；用日志+文案把"为什么没有媒体"说清楚
            val sensitive = root["possibly_sensitive"]?.jsonPrimitive?.contentOrNull == "true"
            val hasMediaEntity = root["entities"]?.jsonObject?.containsKey("media") == true
            ctx.log(
                id,
                "tweet-result 无媒体：sensitive=$sensitive entityMedia=$hasMediaEntity " +
                    "created=${root.str("created_at")} type=${root.str("__typename")}"
            )
            throw ParseException(
                when {
                    sensitive -> "该推文带有敏感内容标记，公开接口与备用镜像都没有拿到媒体，暂时无法下载"
                    hasMediaEntity -> "推文媒体被公开接口隐藏（常见于敏感内容或较老的推文），备用镜像也没有拿到，暂时无法下载"
                    else -> "该推文没有可下载的媒体（可能是纯文本或已删除）"
                },
                platform
            )
        }

        val user = root["user"]?.jsonObject
        return ParserDsl.result(
            platform = platform,
            resolverId = id,
            sourceUrl = url,
            media = media,
            title = root.str("text")?.take(60),
            author = user?.str("name") ?: user?.str("screen_name"),
            cover = media.firstOrNull { it.kind == MediaKind.IMAGE }?.url,
            source = ParseSource.OFFICIAL,
            warning = warning
        )
    }

    /**
     * 把镜像结果里**主通道缺失的媒体**合并进来，按规范化 URL 去重。
     *
     * 为什么不能直接 `media += fallback`：
     * 混合推文里主通道已经拿到了图片，镜像会把同一批图片再给一遍，
     * 直接追加会产生重复条目（用户看到两张一样的图）。
     *
     * 去重键用「去掉 query 的路径」：同一张图在两个通道里可能带不同的
     * `?name=orig` / `?format=jpg` 参数，但路径相同即同一资源。
     *
     * @return 实际新增的条目数
     */
    private fun mergeMissingMedia(target: MutableList<MediaItem>, extra: List<MediaItem>): Int {
        val seen = target.mapTo(mutableSetOf()) { mediaKey(it.url) }
        var added = 0
        extra.forEach { item ->
            val key = mediaKey(item.url)
            if (seen.add(key)) {
                target += item
                added++
            }
        }
        return added
    }

    /** 媒体去重键：剥掉 query 与 fragment，只保留主机+路径 */
    private fun mediaKey(url: String): String =
        url.substringBefore('?').substringBefore('#').trimEnd('/').lowercase()

    /**
     * 兜底通道：第三方公开镜像 fxTwitter（`api.fxtwitter.com/i/status/<id>`，免登录）。
     *
     * 仅在 syndication 拿不到媒体时调用（敏感内容标记、较老推文等），返回
     * `tweet.media.all[]` 里的 pbs 直链（photo → 原图，video/gif → mp4 变体按码率降序）。
     * 镜像不可用/结构变化时静默返回空，由上层给出正常失败文案。
     */
    private fun fetchFxMedia(
        tweetId: String,
        ctx: ParseContext
    ): List<MediaItem> {
        val api = "https://api.fxtwitter.com/i/status/$tweetId"
        val headers = mapOf(
            "Accept" to "application/json",
            "User-Agent" to ctx.ua(desktop = false)
        )
        val body = runCatching { ctx.http.get(api, headers) }.getOrNull()
            ?.takeIf { it.code < 400 }?.body ?: return emptyList()
        val tweet = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrNull()?.get("tweet")?.jsonObject ?: return emptyList()
        val all = tweet["media"]?.jsonObject?.get("all")?.jsonArray ?: return emptyList()
        val xHeaders = mapOf("Referer" to "https://x.com/")

        val out = mutableListOf<MediaItem>()
        all.forEachIndexed { i, el ->
            val o = el.jsonObject
            val type = o.str("type") ?: ""
            when {
                type == "photo" -> {
                    val u = o.str("url") ?: return@forEachIndexed
                    out += MediaItem(
                        id = "x-fx-p-$i",
                        url = if (u.contains("?name=")) u else "$u?name=orig",
                        kind = MediaKind.IMAGE,
                        quality = "原图 ${out.size + 1}",
                        rank = 80 - out.size,
                        container = "jpg",
                        headers = xHeaders
                    )
                }
                type == "video" || type == "gif" || type == "animated_gif" -> {
                    val isGifLike = type != "video"
                    // 阶段 86：GIF 不能只留 mp4。
                    // X 的动图有两种下发形态：① variants 同时给 .mp4（转码）与 .gif（原始）；
                    // ② 只给 .gif。旧实现硬过滤 contains(".mp4")，形态② 时变体列表被清空，
                    // 整条 GIF 直接消失（用户反馈「gif 不会下载」）。
                    val candidates = o["variants"]?.jsonArray
                        ?.mapNotNull { v ->
                            v.jsonObject.str("url")?.let { u -> u to (v.jsonObject.int("bitrate") ?: 0) }
                        }
                        // 只接受视频/动图容器；webm 与 m3u8 在这里没有意义
                        ?.filter { (u, _) ->
                            val base = u.substringBefore('?').lowercase()
                            base.endsWith(".mp4") || base.endsWith(".gif") || base.endsWith(".webm")
                        }
                        // mp4 优先（相册可直接播、体积通常更小），gif 作为备选保留
                        ?.sortedWith(
                            compareByDescending<Pair<String, Int>> {
                                it.first.substringBefore('?').lowercase().endsWith(".mp4")
                            }.thenByDescending { it.second }
                        )
                        .orEmpty()

                    candidates.forEachIndexed { vi, (u, bitrate) ->
                        val isGifFile = u.substringBefore('?').lowercase().endsWith(".gif")
                        out += MediaItem(
                            id = "x-fx-v-$i-$vi",
                            url = u,
                            // GIF 归为 IMAGE：下载侧据此写入 Pictures（相册可播动图），
                            // 若标成 VIDEO 会被塞进 Movies/，系统图库反而打不开。
                            kind = if (isGifFile) MediaKind.IMAGE else MediaKind.VIDEO,
                            quality = when {
                                isGifFile -> if (isGifLike) "GIF 原图" else "GIF 备选"
                                bitrate > 0 -> bitrateLabel(bitrate, vi)
                                isGifLike -> "动图 MP4"
                                else -> bitrateLabel(bitrate, vi)
                            },
                            // 同一媒体内 mp4 排在 gif 前；跨媒体按 i 分段避免撞车
                            rank = 100 - i * 10 - vi,
                            // 关键：gif 必须标 image/gif，否则会被写进 Movies/ 且播放器打不开
                            container = if (isGifFile) "gif" else "mp4",
                            mimeType = if (isGifFile) "image/gif" else "video/mp4",
                            headers = xHeaders
                        )
                    }
                }
            }
        }
        return out
    }

    /**
     * syndication 接口的 guest token 推导：((id / 1e15) * π) 转 36 进制后去掉 '.' 与 '0'。
     * JVM 没有 Double.toString(radix)，这里自行实现整数 + 小数部分的进制转换。
     */
    fun guestToken(tweetId: String): String {
        val id = tweetId.toDoubleOrNull() ?: return ""
        val v = (id / 1e15) * Math.PI
        return toBase36(v).replace(Regex("""0+|\."""), "")
    }

    private fun toBase36(value: Double, fracDigits: Int = 12): String {
        val intPart = value.toLong()
        var frac = value - intPart
        val sb = StringBuilder(intPart.toString(36))
        if (frac > 0.0) {
            sb.append('.')
            repeat(fracDigits) {
                frac *= 36.0
                val d = frac.toLong()
                sb.append(d.toString(36))
                frac -= d.toDouble()
                if (frac <= 0.0) return@repeat
            }
        }
        return sb.toString()
    }

    private fun bitrateLabel(bitrate: Int, index: Int): String = when {
        bitrate > 5_000_000 -> "高码率"
        bitrate > 2_000_000 -> "中码率"
        bitrate > 0 -> "低码率"
        else -> "备选 ${index + 1}"
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
}

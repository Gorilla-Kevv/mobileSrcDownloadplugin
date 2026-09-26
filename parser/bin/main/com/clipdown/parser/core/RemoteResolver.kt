package com.clipdown.parser.core

import com.clipdown.parser.config.ParserConfig
import com.clipdown.parser.http.HttpFacade
import com.clipdown.parser.model.MediaItem
import com.clipdown.parser.model.MediaKind
import com.clipdown.parser.model.ParseResult
import com.clipdown.parser.model.ParseSource
import com.clipdown.parser.model.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 远端解析兜底通道。
 *
 * 设计目标：本地直连解析会因为平台风控随时失效，
 * 因此保留一条"用户可自建"的远端通道（cobalt / yt-dlp 服务端），
 * 本地失败时自动降级到这里，成功率与合规性由用户自己掌控。
 *
 * 协议约定（cobalt 风格，POST /api/json）：
 * 请求：{"url":"...","videoQuality":"1080","alwaysProxy":false}
 * 响应（单资源）：{"status":"stream","url":"https://..."}
 * 响应（多资源）：{"status":"picker","picker":[{"type":"video","url":"..."},{"type":"photo","url":"..."}]}
 */
class RemoteResolver(
    private val config: ParserConfig,
    private val http: HttpFacade
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val available: Boolean
        get() = config.remoteEnabled && config.remoteEndpoint.isNotBlank()

    fun parse(url: String, platform: Platform): ParseResult? {
        if (!available) return null
        val endpoint = config.remoteEndpoint.trimEnd('/')
        val body = JsonObject(
            mapOf(
                "url" to JsonPrimitive(url),
                "videoQuality" to JsonPrimitive("1080"),
                "audioFormat" to JsonPrimitive("mp3"),
                "filenameStyle" to JsonPrimitive("basic")
            )
        ).toString()

        val headers = buildMap {
            put("Accept", "application/json")
            put("Content-Type", "application/json")
            put("User-Agent", config.userAgents.desktop)
            if (config.remoteToken.isNotBlank()) put("Authorization", "Api-Key ${config.remoteToken}")
        }

        val resp = runCatching { http.post("$endpoint/api/json", body, headers) }.getOrNull() ?: return null
        if (!resp.isSuccessful || resp.body.isNullOrBlank()) return null

        return runCatching { toResult(resp.body!!, url, platform) }.getOrNull()
    }

    private fun toResult(raw: String, sourceUrl: String, platform: Platform): ParseResult {
        val root = json.parseToJsonElement(raw).jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull ?: "error"

        val urls = mutableListOf<Pair<String, MediaKind>>()
        when (status) {
            "stream", "redirect" -> {
                root["url"]?.jsonPrimitive?.contentOrNull?.let { urls += it to MediaKind.VIDEO }
            }
            "picker" -> {
                root["picker"]?.jsonArray?.forEach { el ->
                    val o = el.jsonObject
                    val u = o["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val type = o["type"]?.jsonPrimitive?.contentOrNull ?: "video"
                    urls += u to if (type == "photo" || type == "image") MediaKind.IMAGE else MediaKind.VIDEO
                }
            }
            else -> return ParseResult(
                platform = platform,
                resolverId = "remote",
                sourceUrl = sourceUrl,
                source = ParseSource.REMOTE,
                warning = root["text"]?.jsonPrimitive?.contentOrNull ?: "远端解析服务返回错误"
            )
        }

        val media = urls.mapIndexed { index, (u, kind) ->
            MediaItem(
                id = "remote-${u.hashCode().toString(16)}",
                url = u,
                kind = kind,
                quality = "远端解析",
                rank = urls.size - index,
                container = guessContainer(u)
            )
        }

        return ParseResult(
            platform = platform,
            resolverId = "remote",
            sourceUrl = sourceUrl,
            title = root["filename"]?.jsonPrimitive?.contentOrNull,
            media = media,
            source = ParseSource.REMOTE
        )
    }

    private fun guessContainer(u: String): String {
        val clean = u.substringBefore('?')
        val ext = clean.substringAfterLast('.', "")
        return if (ext.length in 3..4) ext else "mp4"
    }
}

package com.clipdown.parser.http

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 轻量 HTTP 结果封装，屏蔽底层实现，便于测试替换 */
data class HttpResponse(
    val code: Int,
    val body: String?,
    val headers: Headers,
    val finalUrl: String
) {
    val isSuccessful: Boolean get() = code in 200..299
    fun header(name: String): String? = headers[name]
}

/** 解析内核依赖的 HTTP 能力抽象 */
interface HttpFacade {

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true
    ): HttpResponse

    fun post(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResponse

    /** 只取响应头，用于短链展开与资源探测（避免下载整包） */
    fun head(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

/**
 * 基于 OkHttp 的默认实现。
 *
 * 说明：cookie 不做全局持久化，解析内核为无状态设计；
 * 需要登录态的平台由上层通过 headers 传入 Cookie（见各 PlatformParser）。
 */
class OkHttpFacade(
    connectTimeoutMs: Long = 10_000,
    readTimeoutMs: Long = 15_000,
    client: OkHttpClient? = null
) : HttpFacade {

    private val client: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(readTimeoutMs + connectTimeoutMs, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResponse {
        val req = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        val c = if (followRedirects) client else client.newBuilder().followRedirects(false).build()
        return c.newCall(req).execute().use { resp ->
            HttpResponse(resp.code, runCatching { resp.body?.string() }.getOrNull(), resp.headers, resp.request.url.toString())
        }
    }

    override fun post(url: String, jsonBody: String, headers: Map<String, String>): HttpResponse {
        val req = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { resp ->
            HttpResponse(resp.code, runCatching { resp.body?.string() }.getOrNull(), resp.headers, resp.request.url.toString())
        }
    }

    override fun head(url: String, headers: Map<String, String>): HttpResponse {
        val req = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .head()
            .build()
        return client.newCall(req).execute().use { resp ->
            HttpResponse(resp.code, null, resp.headers, resp.request.url.toString())
        }
    }
}

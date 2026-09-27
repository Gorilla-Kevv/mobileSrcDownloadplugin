package com.clipdown.parser

import com.clipdown.parser.http.HttpFacade
import com.clipdown.parser.http.HttpResponse
import com.clipdown.parser.model.Platform
import com.clipdown.parser.spi.ParseContext
import okhttp3.Headers
import okhttp3.Headers.Companion.headersOf

/** 测试用假 HTTP 层：按 URL 路由到预置响应 */
class FakeHttp(private val route: (String) -> HttpResponse) : HttpFacade {
    override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): HttpResponse = route(url)
    override fun post(url: String, jsonBody: String, headers: Map<String, String>): HttpResponse = route(url)
    override fun postForm(url: String, formBody: String, headers: Map<String, String>): HttpResponse = route(url)
    override fun head(url: String, headers: Map<String, String>): HttpResponse = route(url)
}

fun fixture(name: String): String =
    FakeHttp::class.java.classLoader.getResourceAsStream(name)!!.readBytes().decodeToString()

fun ok(body: String, headers: Headers = headersOf(), code: Int = 200): HttpResponse =
    HttpResponse(code, body, headers, "")

fun notFound(): HttpResponse = HttpResponse(404, "not found", headersOf(), "")

/** 构造解析上下文：可选 Cookie、webFetcher 与请求记录 */
fun testContext(
    http: HttpFacade,
    cookies: Map<Platform, String> = emptyMap(),
    logs: MutableList<String> = mutableListOf(),
    webFetcher: ((url: String) -> String?)? = null
): ParseContext = ParseContext(
    config = com.clipdown.parser.config.ParserConfig.default(),
    http = http,
    cookieProvider = { cookies[it] },
    logger = { tag, msg -> logs.add("$tag: $msg") },
    webFetcher = webFetcher
)

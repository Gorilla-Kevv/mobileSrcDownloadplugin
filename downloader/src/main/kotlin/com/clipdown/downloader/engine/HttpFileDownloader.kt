package com.clipdown.downloader.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.ProtocolException
import java.util.concurrent.TimeUnit

/**
 * 单文件 HTTP 下载器。
 *
 * 核心能力：
 * - 断点续传：以本地已写入字节数作为 Range 起点，服务端返回 206 时追加写入；
 *   返回 200（不支持 Range）时从头覆盖写。
 * - 取消响应：每个读取循环都检查协程 isActive，取消后不会有残留写入。
 * - 完整性防线：当服务端给出 Content-Length 时，严格校验实际接收长度；
 *   网络过早断开、丢包或 ProtocolException 时统一转换为 [DownloadHttpException] 触发重试，杜绝半截黑图或损坏文件静默落盘。
 * - 拟真 UA 兜底：缺失 User-Agent 时自动注入移动端主流 UA，防范 CDN 针对 okhttp 默认 UA 掐断连接。
 * - 速度统计：调用方按固定窗口读取 [state] 中的累计字节即可换算实时速度。
 */
class HttpFileDownloader(
    private val client: OkHttpClient,
    private val connectTimeoutMs: Long = 15_000,
    private val readTimeoutMs: Long = 30_000
) {

    class State(
        @Volatile var downloaded: Long = 0L,
        @Volatile var total: Long = 0L
    )

    private fun buildClient(): OkHttpClient = client.newBuilder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 执行下载。
     *
     * @param url 直链
     * @param output 目标文件（已存在且长度 > 0 时尝试续传）
     * @param headers 附加请求头（Referer / Cookie 等）
     * @param state 进度容器，下载过程中会持续更新
     * @return 下载完成后文件的实际大小
     */
    @Throws(Exception::class)
    suspend fun download(
        url: String,
        output: File,
        headers: Map<String, String>,
        state: State
    ): Long {
        val existing = if (output.exists()) output.length() else 0L
        state.downloaded = existing

        val req = Request.Builder().url(url).apply {
            val hasUa = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
            if (!hasUa) header("User-Agent", DEFAULT_USER_AGENT)
            headers.forEach { (k, v) -> header(k, v) }
            if (existing > 0) header("Range", "bytes=$existing-")
        }.get().build()

        val http = buildClient()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw DownloadHttpException(resp.code, "下载请求失败：HTTP ${resp.code}")
                }
                val body = resp.body ?: throw DownloadHttpException(-1, "响应体为空")

                val resumed = resp.code == 206
                val contentLength = body.contentLength()
                state.total = if (contentLength > 0) {
                    if (resumed) existing + contentLength else contentLength
                } else state.total

                val mode = if (resumed && existing > 0) "rw" else "rw"
                RandomAccessFile(output, mode).use { raf ->
                    if (!resumed) raf.setLength(0)
                    if (resumed) raf.seek(existing)

                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (true) {
                            if (!currentCoroutineContext().isActive) {
                                throw DownloadCanceledException()
                            }
                            read = input.read(buffer)
                            if (read == -1) break
                            raf.write(buffer, 0, read)
                            state.downloaded += read
                        }
                    }
                }
            }
        } catch (e: ProtocolException) {
            throw DownloadHttpException(-1, "下载数据未接收完整：网络传输提前中断（${e.message}）")
        }

        // 完整性断言：服务端给出长度时，校验实际落盘大小，杜绝网络中断未读完却被当做完成的情况
        val actualLength = output.length()
        if (state.total > 0 && actualLength < state.total) {
            throw DownloadHttpException(
                -1,
                "下载数据未接收完整：预期 ${state.total} 字节，实际仅接收 $actualLength 字节（网络传输提前中断）"
            )
        }

        // 服务端未给出长度时，以落盘大小回填
        if (state.total <= 0) state.total = state.downloaded
        return actualLength
    }

    /** 只探测资源元信息，用于解析阶段补全大小与 MIME */
    fun probe(url: String, headers: Map<String, String>): ProbeResult {
        val req = Request.Builder().url(url).apply {
            val hasUa = headers.keys.any { it.equals("User-Agent", ignoreCase = true) }
            if (!hasUa) header("User-Agent", DEFAULT_USER_AGENT)
            headers.forEach { (k, v) -> header(k, v) }
        }.head().build()
        return buildClient().newCall(req).execute().use { resp ->
            ProbeResult(
                code = resp.code,
                contentLength = resp.header("Content-Length")?.toLongOrNull() ?: 0L,
                mimeType = resp.header("Content-Type"),
                acceptRanges = resp.header("Accept-Ranges")?.contains("bytes") == true
            )
        }
    }

    data class ProbeResult(
        val code: Int,
        val contentLength: Long,
        val mimeType: String?,
        val acceptRanges: Boolean
    )

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }
}

class DownloadHttpException(val code: Int, message: String) : Exception(message)
class DownloadCanceledException : Exception("下载已取消")

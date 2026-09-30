package com.clipdown.downloader

import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 极简 HTTP 测试服务器（仅 JDK Socket，无新增依赖）。
 *
 * 设计要点：
 * - 路由按 path 返回 [TestResponse]，支持 Range（206）/ 全量（200）/ 任意状态码；
 * - 记录每次请求（method/path/headers）供断言（如核对续传 Range 头）；
 * - 支持分块慢速响应，用于验证取消语义。
 */
internal class TestResponse(
    val code: Int = 200,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "application/octet-stream",
    val acceptRanges: Boolean = true,
    val chunkSize: Int = 0,
    val chunkDelayMs: Long = 0L
)

internal class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>
)

internal class TestHttpServer(
    private val handler: (path: String) -> TestResponse?
) : AutoCloseable {

    @Volatile
    private var running = true

    private val socket = ServerSocket(0)
    private val pool = Executors.newCachedThreadPool()
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"
    fun url(path: String): String = baseUrl + path

    init {
        pool.submit { acceptLoop() }
    }

    private fun acceptLoop() {
        while (running) {
            val conn = try {
                socket.accept()
            } catch (e: Exception) {
                return
            }
            pool.submit { serve(conn) }
        }
    }

    private fun serve(conn: java.net.Socket) {
        try {
            conn.use { c ->
                val head = readHead(c.getInputStream())
                if (head.isEmpty()) return
                val lines = head.split("\r\n")
                val parts = lines[0].split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]
                val headers = lines.drop(1).mapNotNull { l ->
                    val i = l.indexOf(':')
                    if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
                }.toMap()
                requests.add(RecordedRequest(method, path, headers))
                writeResponse(c.getOutputStream(), method, handler(path) ?: TestResponse(404), headers["range"])
            }
        } catch (e: Exception) {
            // 测试期客户端可能提前断开（取消场景），忽略
        }
    }

    private fun readHead(input: java.io.InputStream): String {
        val buf = java.io.ByteArrayOutputStream()
        val window = ByteArray(4)
        var matched = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            buf.write(b)
            window[matched] = b.toByte()
            matched++
            if (matched == 4) {
                if (window[0] == '\r'.code.toByte() && window[1] == '\n'.code.toByte() &&
                    window[2] == '\r'.code.toByte() && window[3] == '\n'.code.toByte()
                ) break
                System.arraycopy(window, 1, window, 0, 3)
                matched = 3
            }
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }

    private fun writeResponse(out: OutputStream, method: String, resp: TestResponse, range: String?) {
        if (resp.code != 200) {
            out.write("HTTP/1.1 ${resp.code} Status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            return
        }

        val r = range?.let { parseRange(it, resp.body.size) }
        val payload: ByteArray
        val status: String
        val extra: String
        if (r != null && resp.acceptRanges) {
            payload = resp.body.copyOfRange(r.first, r.second + 1)
            status = "HTTP/1.1 206 Partial Content"
            extra = "Content-Range: bytes ${r.first}-${r.second}/${resp.body.size}\r\n"
        } else {
            payload = resp.body
            status = "HTTP/1.1 200 OK"
            extra = if (resp.acceptRanges) "Accept-Ranges: bytes\r\n" else ""
        }

        val head = "$status\r\n" +
            "Content-Type: ${resp.contentType}\r\n" +
            "Content-Length: ${payload.size}\r\n" +
            extra +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (method == "HEAD") {
            out.flush()
            return
        }
        if (resp.chunkSize > 0 && payload.isNotEmpty()) {
            var off = 0
            while (off < payload.size) {
                val n = minOf(resp.chunkSize, payload.size - off)
                out.write(payload, off, n)
                out.flush()
                off += n
                Thread.sleep(resp.chunkDelayMs)
            }
        } else {
            out.write(payload)
        }
        out.flush()
    }

    private fun parseRange(value: String, size: Int): Pair<Int, Int>? {
        val spec = value.substringAfter("bytes=", "").substringBefore(',')
        if (spec.isBlank()) return null
        val start = spec.substringBefore('-').trim().toIntOrNull() ?: return null
        val endRaw = spec.substringAfter('-', "").trim()
        val end = if (endRaw.isEmpty()) size - 1 else endRaw.toIntOrNull() ?: (size - 1)
        if (start >= size || start > end) return null
        return start to minOf(end, size - 1)
    }

    override fun close() {
        running = false
        runCatching { socket.close() }
        pool.shutdownNow()
    }
}

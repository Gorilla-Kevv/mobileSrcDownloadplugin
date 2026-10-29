package com.clipdown.app.sync

import android.content.Context
import android.os.Build
import android.util.Log
import com.clipdown.app.BuildConfig
import com.clipdown.app.clip.LinkCenter
import com.clipdown.app.clip.LinkSource
import com.clipdown.app.data.CookieStore
import com.clipdown.downloader.TaskRepository
import com.clipdown.downloader.model.DownloadStatus
import com.clipdown.downloader.model.TaskEntity
import com.clipdown.downloader.model.TaskKind
import com.clipdown.parser.model.Platform
import com.clipdown.parser.parsers.IgRiskGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 轻量级嵌入式局域网同步服务器。
 *
 * 基于标准 Java ServerSocket + Kotlin 协程实现，零外部重型依赖，完全不增加包体积。
 * 供电脑端 Edge / Chrome 插件进行局域网直连：
 * 1. 共享登录态（将电脑浏览器提取的 Instagram / 小红书等 Cookie 秒传手机）；
 * 2. 同步下载记录（双向同步元数据记录，不传输庞大媒体文件）；
 * 3. 网页媒体快捷嗅探推送（从 PC 浏览器一键推送到手机下载中枢）。
 */
class SyncServer(
    private val context: Context,
    val port: Int = 8765,
    private val pinProvider: () -> String
) {

    private val tag = "SyncServer"
    private var serverSocket: ServerSocket? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var acceptJob: Job? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun start() {
        if (serverSocket != null) return
        try {
            serverSocket = ServerSocket(port).apply {
                reuseAddress = true
            }
            Log.i(tag, "ClipDown LAN SyncServer started on port $port")
            acceptJob = serverScope.launch {
                while (isActive) {
                    val socket = try {
                        serverSocket?.accept() ?: break
                    } catch (e: Exception) {
                        if (!isActive) break
                        Log.w(tag, "Accept failed: ${e.message}")
                        continue
                    }
                    launch {
                        handleClient(socket)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start SyncServer on port $port", e)
            close()
        }
    }

    fun close() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverScope.cancel()
        Log.i(tag, "SyncServer stopped")
    }

    val isRunning: Boolean
        get() = serverSocket != null && serverSocket?.isClosed == false

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 15_000
            val input = s.getInputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
            val output = s.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1]

            // 解析 Headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val colon = line!!.indexOf(':')
                if (colon > 0) {
                    val key = line!!.substring(0, colon).trim().lowercase()
                    val value = line!!.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            // 处理 CORS 预检请求
            if (method == "OPTIONS") {
                sendOptions(output)
                return
            }

            // 读取 Body
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val read = reader.read(buf, readTotal, contentLength - readTotal)
                    if (read == -1) break
                    readTotal += read
                }
                String(buf, 0, readTotal)
            } else ""

            // 解析 Path 与 Query
            val pathParts = rawPath.split("?", limit = 2)
            val path = pathParts[0]
            val queryParams = mutableMapOf<String, String>()
            if (pathParts.size > 1) {
                pathParts[1].split("&").forEach { pair ->
                    val kv = pair.split("=", limit = 2)
                    val k = URLDecoder.decode(kv[0], "UTF-8")
                    val v = if (kv.size > 1) URLDecoder.decode(kv[1], "UTF-8") else ""
                    queryParams[k] = v
                }
            }

            // PIN 安全验证
            val clientPin = headers["x-sync-pin"] ?: queryParams["pin"] ?: ""
            val expectedPin = pinProvider()
            val pinValid = clientPin.isNotBlank() && clientPin == expectedPin

            when {
                // 探活与状态检测（允许免 PIN 检测，但 paired 字段表明 PIN 是否匹配）
                path == "/api/status" -> {
                    handleStatus(output, pinValid)
                }

                // 需要验证 PIN 的路由
                !pinValid -> {
                    sendJson(output, 401, "Unauthorized", json.encodeToString(
                        SyncApiResponse(success = false, message = "配对 PIN 码错误或未提供")
                    ))
                }

                path == "/api/cookies" && method == "GET" -> {
                    handleGetCookies(output)
                }

                path == "/api/cookies" && method == "POST" -> {
                    handlePostCookies(output, body)
                }

                path == "/api/tasks" && method == "GET" -> {
                    handleGetTasks(output)
                }

                path == "/api/tasks" && method == "POST" -> {
                    handlePostTasks(output, body)
                }

                path == "/api/enqueue" && method == "POST" -> {
                    handleEnqueue(output, body)
                }

                else -> {
                    sendJson(output, 404, "Not Found", json.encodeToString(
                        SyncApiResponse(success = false, message = "未找到接口: $path")
                    ))
                }
            }
        }
    }

    private fun handleStatus(output: OutputStream, paired: Boolean) {
        val platformsWithCookies = Platform.entries.filter { CookieStore.has(context, it) }.map { it.id }
        val taskCount = runCatching { TaskRepository.get().tasks.value.size }.getOrDefault(0)
        val resp = SyncStatusResponse(
            deviceName = "${Build.BRAND.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            appVersion = BuildConfig.VERSION_NAME,
            port = port,
            paired = paired,
            taskCount = taskCount,
            loggedInPlatforms = platformsWithCookies
        )
        sendJson(output, 200, "OK", json.encodeToString(resp))
    }

    private fun handleGetCookies(output: OutputStream) {
        val platforms = Platform.entries.filter { it != Platform.GENERIC }.associate { p ->
            p.id to CookieStore.has(context, p)
        }
        sendJson(output, 200, "OK", json.encodeToString(platforms))
    }

    private fun handlePostCookies(output: OutputStream, body: String) {
        if (body.isBlank()) {
            sendJson(output, 400, "Bad Request", json.encodeToString(
                SyncApiResponse(success = false, message = "请求体为空")
            ))
            return
        }

        var savedCount = 0
        val savedPlatforms = mutableListOf<String>()

        try {
            // 尝试按批量 Map 格式解析
            val rootObj = json.decodeFromString<JsonObject>(body)
            if (rootObj.containsKey("cookies")) {
                val cookiesObj = rootObj["cookies"] as? JsonObject
                cookiesObj?.forEach { (platId, cookieElem) ->
                    val cookieStr = cookieElem.jsonPrimitive.content
                    if (saveCookieForPlatform(platId, cookieStr)) {
                        savedCount++
                        savedPlatforms.add(platId)
                    }
                }
            } else if (rootObj.containsKey("platform") && rootObj.containsKey("cookie")) {
                val platId = rootObj["platform"]?.jsonPrimitive?.content ?: ""
                val cookieStr = rootObj["cookie"]?.jsonPrimitive?.content ?: ""
                if (saveCookieForPlatform(platId, cookieStr)) {
                    savedCount++
                    savedPlatforms.add(platId)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Parse cookie request failed: ${e.message}")
            sendJson(output, 400, "Bad Request", json.encodeToString(
                SyncApiResponse(success = false, message = "解析 Cookie 数据失败: ${e.message}")
            ))
            return
        }

        sendJson(output, 200, "OK", json.encodeToString(
            SyncApiResponse(
                success = savedCount > 0,
                message = if (savedCount > 0) "成功同步 $savedCount 个平台的登录态" else "未能识别有效登录态 Cookie",
                data = savedPlatforms.joinToString(",")
            )
        ))
    }

    private fun saveCookieForPlatform(platId: String, cookie: String): Boolean {
        if (cookie.isBlank()) return false
        val platform = Platform.entries.find { it.id.equals(platId, ignoreCase = true) }
            ?: Platform.entries.find { it.name.equals(platId, ignoreCase = true) }
            ?: return false

        // 校验核心会话令牌
        if (!CookieStore.isValidSessionCookie(platform, cookie)) {
            Log.w(tag, "Platform $platId cookie invalid, missing session token")
            return false
        }

        CookieStore.put(context, platform, cookie)
        if (platform == Platform.INSTAGRAM) {
            IgRiskGuard.reset()
        }
        Log.i(tag, "Successfully imported cookie for ${platform.displayName} from PC extension")
        return true
    }

    private fun handleGetTasks(output: OutputStream) {
        val tasks = runCatching { TaskRepository.get().tasks.value }.getOrDefault(emptyList())
        val items = tasks.map { t ->
            SyncTaskItem(
                id = t.id,
                title = t.title,
                url = t.url,
                fileName = t.fileName,
                mimeType = t.mimeType,
                container = t.container,
                kind = t.kind.name,
                platformId = t.platformId,
                coverUrl = t.coverUrl,
                sourceUrl = t.sourceUrl,
                totalBytes = t.totalBytes,
                downloadedBytes = t.downloadedBytes,
                status = t.status.name,
                createdAt = t.createdAt,
                updatedAt = t.updatedAt
            )
        }
        val resp = SyncTasksResponse(total = items.size, tasks = items)
        sendJson(output, 200, "OK", json.encodeToString(resp))
    }

    private fun handlePostTasks(output: OutputStream, body: String) {
        if (body.isBlank()) {
            sendJson(output, 400, "Bad Request", json.encodeToString(
                SyncApiResponse(success = false, message = "请求体为空")
            ))
            return
        }

        try {
            val req = json.decodeFromString<SyncTasksBatchRequest>(body)
            val converted = req.tasks.map { item ->
                TaskEntity(
                    id = item.id,
                    title = item.title,
                    url = item.url,
                    fileName = item.fileName,
                    mimeType = item.mimeType,
                    container = item.container,
                    kind = runCatching { TaskKind.valueOf(item.kind) }.getOrDefault(TaskKind.SINGLE),
                    platformId = item.platformId,
                    coverUrl = item.coverUrl,
                    sourceUrl = item.sourceUrl,
                    totalBytes = item.totalBytes,
                    downloadedBytes = item.downloadedBytes,
                    status = runCatching { DownloadStatus.valueOf(item.status) }.getOrDefault(DownloadStatus.COMPLETED),
                    createdAt = if (item.createdAt > 0) item.createdAt else System.currentTimeMillis(),
                    updatedAt = if (item.updatedAt > 0) item.updatedAt else System.currentTimeMillis()
                )
            }
            TaskRepository.get().saveRecords(converted)
            sendJson(output, 200, "OK", json.encodeToString(
                SyncApiResponse(success = true, message = "成功从电脑端同步导入 ${converted.size} 条下载记录")
            ))
        } catch (e: Exception) {
            Log.e(tag, "Failed to import tasks", e)
            sendJson(output, 400, "Bad Request", json.encodeToString(
                SyncApiResponse(success = false, message = "导入任务记录失败: ${e.message}")
            ))
        }
    }

    private fun handleEnqueue(output: OutputStream, body: String) {
        if (body.isBlank()) {
            sendJson(output, 400, "Bad Request", json.encodeToString(
                SyncApiResponse(success = false, message = "请求体为空")
            ))
            return
        }

        try {
            val req = json.decodeFromString<SyncEnqueueRequest>(body)
            val url = req.url.trim()
            if (url.isBlank()) {
                sendJson(output, 400, "Bad Request", json.encodeToString(
                    SyncApiResponse(success = false, message = "下载 URL 为空")
                ))
                return
            }
            val detected = LinkCenter.submit(url, LinkSource.PC_SYNC, force = true)
            if (detected != null) {
                sendJson(output, 200, "OK", json.encodeToString(
                    SyncApiResponse(success = true, message = "已将链接推送到手机下载识别中枢: ${detected.platform.displayName}")
                ))
            } else {
                sendJson(output, 200, "OK", json.encodeToString(
                    SyncApiResponse(success = true, message = "链接已提交到手机，正在识别中...")
                ))
            }
        } catch (e: Exception) {
            sendJson(output, 400, "Bad Request", json.encodeToString(
                SyncApiResponse(success = false, message = "处理推送下载失败: ${e.message}")
            ))
        }
    }

    private fun sendJson(out: OutputStream, statusCode: Int, statusText: String, bodyJson: String) {
        val bytes = bodyJson.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, X-Sync-PIN, Authorization\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun sendOptions(out: OutputStream) {
        val header = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, X-Sync-PIN, Authorization\r\n" +
                "Access-Control-Max-Age: 86400\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.flush()
    }
}

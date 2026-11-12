package com.clipdown.app.sync

import java.net.URI
import java.net.URLDecoder

/**
 * 电脑端扩展展示的「配对二维码」载荷。
 *
 * 方向约定（阶段 83 修正）：**电脑显示二维码、手机扫码**。
 *
 * 为什么二维码里装的是**手机自己的地址**：
 * 扩展是 MV3，没有监听端口的能力，手机扫完无法"回连电脑"。
 * 因此由电脑先用「自动搜寻 / 已连接」拿到手机的 `http://IP:PORT` 与 PIN，
 * 再把这对凭据编进二维码；手机扫到后即确认「本机就是被配对的这台手机」，
 * 立刻把配对态落库并提示成功。全程不需要电脑起服务，也不需要手机回连。
 *
 * 载荷格式（兼容两种键名）：
 * - `clipdown://pair?url=http://IP:PORT&pin=123456&token=...`（新，推荐）
 * - `clipdown://pair?pc=http://IP:PORT&token=...`（旧，仅含地址时按地址解析）
 */
data class PairPayloadData(
    /** 手机（本机）的局域网服务地址 */
    val phoneUrl: String,
    /** 6 位配对 PIN，可能为空（旧载荷） */
    val pin: String,
    /** 一次性配对令牌，仅用于展示与防误扫 */
    val token: String
)

object PairPayload {

    /**
     * 解析扫码文本。只接受 `clipdown://pair`，其余形态返回 null，
     * 避免把旧版「手机→电脑」口令误当成本方向。
     */
    fun parse(text: String?): PairPayloadData? {
        val raw = text?.trim() ?: return null
        if (!raw.startsWith("clipdown://pair", ignoreCase = true)) return null
        val query = raw.substringAfter('?', "")
        if (query.isBlank()) return null

        val params = query.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else decode(pair.substring(0, i)) to decode(pair.substring(i + 1))
        }.toMap()

        val url = params["url"] ?: params["pc"] ?: return null
        if (!isValidHttpUrl(url)) return null
        return PairPayloadData(
            phoneUrl = url.trimEnd('/'),
            pin = params["pin"].orEmpty().filter { it.isDigit() }.take(6),
            token = params["token"].orEmpty()
        )
    }

    /** 地址形态校验：http(s) + 合法 host（端口可缺省） */
    fun isValidHttpUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host ?: return false
        if (host.isBlank()) return false
        return uri.port == -1 || uri.port in 1..65535
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

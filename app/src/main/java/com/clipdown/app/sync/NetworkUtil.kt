package com.clipdown.app.sync

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 本地局域网网络工具。
 * 遍历本机可用网络接口，检索非回环的 IPv4 局域网地址（如 192.168.x.x / 10.x.x.x / 172.16-31.x.x），
 * 供电脑端 Edge / Chrome 插件直连配对。
 */
object NetworkUtil {

    fun getLocalIpAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var candidate: String? = null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                val isWifiOrHotspot = name.contains("wlan") || name.contains("ap") || name.contains("eth")
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || isPrivate172(ip)) {
                            // 优先选取 wlan / ap 接口地址
                            if (isWifiOrHotspot) return ip
                            if (candidate == null) candidate = ip
                        }
                    }
                }
            }
            candidate
        }.getOrNull()
    }

    private fun isPrivate172(ip: String): Boolean {
        if (!ip.startsWith("172.")) return false
        val parts = ip.split('.')
        if (parts.size < 2) return false
        val second = parts[1].toIntOrNull() ?: return false
        return second in 16..31
    }
}

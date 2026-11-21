package com.clipdown.downloader.engine

import java.io.File
import java.io.RandomAccessFile

/**
 * 媒体文件完整性校验器。
 *
 * 核心目标：
 * 杜绝网络连接异常断开（如手机端代理/VPN中断、CDN静默关闭连接）导致的"半截黑图"、"文件截断"却被静默标记为已完成的硬伤。
 *
 * 校验规则：
 * 1. 基础大小与预期长度：文件非空，且当 HTTP 响应头给出确定长度时实际接收字节数必须达标；
 * 2. JPEG 格式完整性（重点）：必须以 SOI (0xFF, 0xD8) 起始，且文件尾部 256 字节内必须含有 EOI (0xFF, 0xD9) 结束标记；
 *    若未检测到 EOI，判定为传输中断截断的残缺图片，抛出 [DownloadHttpException] 触发断点续传或重试；
 * 3. PNG 格式完整性：必须以 PNG 魔数起始，且尾部 128 字节内必须含有 IEND chunk。
 * 4. GIF 格式完整性（阶段 86）：头部必须是 GIF87a/GIF89a，尾部必须有 Trailer 0x3B。
 */
object MediaIntegrityValidator {

    @Throws(DownloadHttpException::class)
    fun validate(file: File, expectedLength: Long = -1L, mimeType: String? = null) {
        if (!file.exists() || file.length() == 0L) {
            throw DownloadHttpException(-1, "下载文件为空")
        }

        val actualLength = file.length()
        if (expectedLength > 0 && actualLength < expectedLength) {
            throw DownloadHttpException(
                -1,
                "下载数据未接收完整：预期 $expectedLength 字节，实际仅接收 $actualLength 字节（网络传输提前中断）"
            )
        }

        val mime = (mimeType ?: "").lowercase()
        val name = file.name.lowercase()
        val isJpeg = mime.contains("jpeg") || mime.contains("jpg") || name.endsWith(".jpg") || name.endsWith(".jpeg")
        val isPng = mime.contains("png") || name.endsWith(".png")
        val isGif = mime.contains("gif") || name.endsWith(".gif")

        if (isJpeg && actualLength >= 4) {
            validateJpeg(file, actualLength)
        } else if (isPng && actualLength >= 16) {
            validatePng(file, actualLength)
        } else if (isGif && actualLength >= 6) {
            validateGif(file, actualLength)
        }
    }

    /**
     * GIF 完整性校验（阶段 86 补齐）。
     *
     * 之前完全没有 GIF 分支：动图下载被截断时不会触发重试，
     * 用户拿到一张裂图却显示"已完成"。这里校验两件事：
     * 1. 头部必须是 `GIF87a` 或 `GIF89a`（拒绝把 HTML 错误页当动图存下来）；
     * 2. 尾部必须出现块结束符 `0x3B`（Trailer），允许尾部有少量填充。
     */
    private fun validateGif(file: File, length: Long) {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(6)
            raf.readFully(header)
            val magic = String(header, Charsets.US_ASCII)
            if (magic != "GIF87a" && magic != "GIF89a") {
                throw DownloadHttpException(-1, "无效的 GIF 文件头（可能是错误页而非动图）")
            }

            // 尾部 64 字节内找 Trailer (0x3B)
            val checkSize = minOf(length, 64L).toInt()
            raf.seek(length - checkSize)
            val tail = ByteArray(checkSize)
            raf.readFully(tail)
            if (tail.none { it == 0x3B.toByte() }) {
                throw DownloadHttpException(
                    -1,
                    "GIF 动图数据被截断：未读取到结束块（文件受损/仅接收到部分图像）"
                )
            }
        }
    }

    private fun validateJpeg(file: File, length: Long) {
        RandomAccessFile(file, "r").use { raf ->
            // 校验头部 SOI (0xFF, 0xD8)
            val header = ByteArray(2)
            raf.readFully(header)
            if (header[0] != 0xFF.toByte() || header[1] != 0xD8.toByte()) {
                throw DownloadHttpException(-1, "无效的 JPEG 文件头（非标准图像）")
            }

            // 校验尾部 EOI (0xFF, 0xD9)
            // 扫描最后 256 字节以兼容部分服务端带有的少量 padding / 空白字符
            val checkSize = minOf(length, 256L).toInt()
            raf.seek(length - checkSize)
            val tail = ByteArray(checkSize)
            raf.readFully(tail)

            var foundEoi = false
            for (i in 0 until checkSize - 1) {
                if (tail[i] == 0xFF.toByte() && tail[i + 1] == 0xD9.toByte()) {
                    foundEoi = true
                    break
                }
            }
            if (!foundEoi) {
                throw DownloadHttpException(
                    -1,
                    "JPEG 图片数据被截断：未读取到 EOI 结束标记（文件受损/仅接收到部分图像）"
                )
            }
        }
    }

    private fun validatePng(file: File, length: Long) {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(8)
            raf.readFully(header)
            // PNG 魔法头: 89 50 4E 47 0D 0A 1A 0A
            if (header[0] != 0x89.toByte() || header[1] != 0x50.toByte()) {
                throw DownloadHttpException(-1, "无效的 PNG 文件头")
            }

            // PNG 结尾 chunk: IEND (0x49, 0x45, 0x4E, 0x44)
            val checkSize = minOf(length, 128L).toInt()
            raf.seek(length - checkSize)
            val tail = ByteArray(checkSize)
            raf.readFully(tail)

            val iend = byteArrayOf(0x49, 0x45, 0x4E, 0x44)
            var foundIend = false
            for (i in 0..checkSize - 4) {
                if (tail[i] == iend[0] && tail[i + 1] == iend[1] && tail[i + 2] == iend[2] && tail[i + 3] == iend[3]) {
                    foundIend = true
                    break
                }
            }
            if (!foundIend) {
                throw DownloadHttpException(-1, "PNG 图片数据被截断：未读取到 IEND 结束标记")
            }
        }
    }
}

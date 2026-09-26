package com.clipdown.downloader.engine

import com.clipdown.parser.stream.M3u8Parser
import com.clipdown.parser.stream.M3u8Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS（m3u8）分片下载器。
 *
 * 流程：拉取主列表 → 选档 → 拉取媒体列表 → 并发下载分片 → AES-128 解密 → 顺序拼接 → 尽力封装为 MP4。
 *
 * 进度换算：分片总字节数在下载前不可知，这里用"已完成分片的平均大小 × 分片总数"做估算，
 * 保证进度条单调增长且不会超过 100%。
 */
class M3u8Downloader(
    private val client: OkHttpClient,
    private val segmentConcurrency: Int = 3
) {

    suspend fun download(
        playlistUrl: String,
        headers: Map<String, String>,
        workDir: File,
        output: File,
        state: HttpFileDownloader.State,
        variantIndex: Int = 0
    ): Boolean = withContext(Dispatchers.IO) {
        workDir.mkdirs()

        val masterText = fetchText(playlistUrl, headers) ?: return@withContext false
        var playlist = M3u8Parser.parse(masterText, playlistUrl)

        if (playlist.isMaster) {
            val variant = M3u8Parser.pickVariant(playlist, variantIndex) ?: return@withContext false
            val mediaText = fetchText(variant.url, headers) ?: return@withContext false
            playlist = M3u8Parser.parse(mediaText, variant.url)
        }
        if (playlist.segments.isEmpty()) return@withContext false

        val segments = playlist.segments
        val total = segments.size
        var completed = 0
        var bytesSum = 0L

        val files = coroutineScope {
            segments.mapIndexed { index, segment ->
                async(Dispatchers.IO) {
                    if (!isActive) return@async null
                    val size = downloadSegment(segment, headers, index, workDir)
                    synchronized(this@M3u8Downloader) {
                        completed++
                        bytesSum += size
                        val avg = if (completed == 0) 0L else bytesSum / completed
                        state.downloaded = bytesSum
                        state.total = (avg * total).coerceAtLeast(bytesSum)
                    }
                    File(workDir, String.format(Locale.US, "seg_%05d.ts", index))
                }
            }.awaitAll().filterNotNull()
        }

        if (files.size < total) return@withContext false

        val merged = File(workDir, "merged.ts")
        if (!MediaRemuxer.concatSegments(files.sortedBy { it.name }, merged)) return@withContext false

        val remuxed = MediaRemuxer.remuxToMp4(merged, output)
        if (!remuxed) {
            merged.copyTo(output, overwrite = true)
        }
        merged.delete()

        state.downloaded = output.length()
        state.total = output.length()
        true
    }

    /** 下载单个分片：带 AES-128 解密与字节范围支持 */
    private fun downloadSegment(
        segment: M3u8Segment,
        headers: Map<String, String>,
        index: Int,
        workDir: File
    ): Long {
        val req = Request.Builder().url(segment.uri).apply {
            headers.forEach { (k, v) -> header(k, v) }
            segment.byteRange?.let { (len, off) -> header("Range", "bytes=$off-${off + len - 1}") }
        }.get().build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw DownloadHttpException(resp.code, "分片下载失败：${resp.code}")
            val raw = resp.body?.bytes() ?: throw DownloadHttpException(-1, "分片内容为空")
            val data = decrypt(raw, segment, headers, index)
            File(workDir, String.format(Locale.US, "seg_%05d.ts", index)).writeBytes(data)
            return data.size.toLong()
        }
    }

    private fun decrypt(
        raw: ByteArray,
        segment: M3u8Segment,
        headers: Map<String, String>,
        index: Int
    ): ByteArray {
        val key = segment.key
        if (key == null || !key.isAes128) return raw
        val keyUri = key.uri ?: return raw
        val keyBytes = fetchBytes(keyUri, headers) ?: return raw
        // 未显式声明 IV 时，HLS 约定使用分片序号的 16 字节大端表示
        val iv = key.ivHex?.let { hexToBytes(it) } ?: sequenceIv(index)
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            cipher.doFinal(raw)
        }.getOrDefault(raw)
    }

    private fun sequenceIv(sequence: Int): ByteArray = ByteArray(16).apply {
        this[12] = (sequence ushr 24).toByte()
        this[13] = (sequence ushr 16).toByte()
        this[14] = (sequence ushr 8).toByte()
        this[15] = sequence.toByte()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.removePrefix("0x").removePrefix("0X")
        return ByteArray(16) { i ->
            if (i * 2 + 1 < s.length) s.substring(i * 2, i * 2 + 2).toInt(16).toByte() else 0
        }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        return client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        return client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }
}

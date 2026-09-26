package com.clipdown.downloader.engine

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 媒体重封装工具（自研，不依赖 ffmpeg）。
 *
 * 两种用法：
 * 1. [concatInitAndMedia]：DASH / fMP4 场景，init 分片 + 媒体分片顺序拼接即为可播放文件，
 *    无需逐样本处理，成本最低；
 * 2. [remuxToMp4]：HLS TS 场景，用 MediaExtractor 读出音视频轨，再用 MediaMuxer 写成 MP4。
 *    这是"能成功就成功、失败就降级"的尽力而为策略：某些 TS 封装 Android 无法解复用，
 *    此时返回 false，调用方保留原始 TS 文件即可（绝大多数播放器可直接播放）。
 */
object MediaRemuxer {

    private const val DEFAULT_BUFFER = 2 * 1024 * 1024

    /** 拼接 init 分片与媒体分片，输出单个文件 */
    fun concatInitAndMedia(init: File, media: File, out: File): Boolean {
        return runCatching {
            FileOutputStream(out, false).use { os ->
                init.inputStream().use { it.copyTo(os) }
                media.inputStream().use { it.copyTo(os) }
            }
            out.length() > 0
        }.getOrDefault(false)
    }

    /** 顺序拼接多个分片（HLS 的 ts 分片） */
    fun concatSegments(segments: List<File>, out: File): Boolean {
        return runCatching {
            FileOutputStream(out, false).use { os ->
                segments.forEach { f ->
                    FileInputStream(f).use { it.copyTo(os) }
                }
            }
            out.length() > 0
        }.getOrDefault(false)
    }

    /**
     * 合并分离的视频轨与音频轨（DASH 场景）为单个 MP4。
     *
     * 实现要点：两个 MediaExtractor 各自解复用，MediaMuxer 按 presentationTimeUs 顺序写入，
     * 时间基不同的情况下以各自 sampleTime 为准，播放器侧可正常同步。
     */
    fun mergeTracks(video: File, audio: File, out: File): Boolean {
        if (!video.exists() || !audio.exists()) return false
        val ve = MediaExtractor()
        val ae = MediaExtractor()
        return try {
            ve.setDataSource(video.absolutePath)
            ae.setDataSource(audio.absolutePath)
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val vTrack = (0 until ve.trackCount).firstOrNull {
                ve.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return false
            val aTrack = (0 until ae.trackCount).firstOrNull {
                ae.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run { muxer.release(); return false }

            val vIdx = muxer.addTrack(ve.getTrackFormat(vTrack))
            val aIdx = muxer.addTrack(ae.getTrackFormat(aTrack))
            muxer.start()

            ve.selectTrack(vTrack)
            ae.selectTrack(aTrack)
            writeTrack(ve, vIdx, muxer)
            writeTrack(ae, aIdx, muxer)

            muxer.stop()
            muxer.release()
            out.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            ve.release()
            ae.release()
        }
    }

    private fun writeTrack(extractor: MediaExtractor, muxIndex: Int, muxer: MediaMuxer) {
        val info = android.media.MediaCodec.BufferInfo()
        val buffer = java.nio.ByteBuffer.allocateDirect(DEFAULT_BUFFER)
        while (true) {
            info.offset = 0
            info.size = extractor.readSampleData(buffer, 0)
            if (info.size < 0) break
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(muxIndex, buffer, info)
            extractor.advance()
        }
    }

    /**
     * 将输入文件重封装为 MP4。
     *
     * @return true 表示成功；失败时调用方应保留源文件
     */
    fun remuxToMp4(input: File, out: File): Boolean {
        if (!input.exists() || input.length() == 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(input.absolutePath)
            val trackCount = extractor.trackCount
            if (trackCount == 0) return false

            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val indexMap = HashMap<Int, Int>(trackCount)

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                indexMap[i] = muxer.addTrack(format)
            }
            if (indexMap.isEmpty()) {
                muxer.release()
                return false
            }

            muxer.start()
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            val buffer = java.nio.ByteBuffer.allocateDirect(DEFAULT_BUFFER)

            var sawEos = 0
            while (sawEos < indexMap.size) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) {
                    sawEos = indexMap.size
                    break
                }
                val muxIndex = indexMap[trackIndex]
                if (muxIndex == null) {
                    extractor.advance()
                    continue
                }
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    sawEos++
                    extractor.advance()
                    continue
                }
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxIndex, buffer, bufferInfo)
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            out.length() > 0
        } catch (e: Exception) {
            false
        } finally {
            extractor.release()
        }
    }
}

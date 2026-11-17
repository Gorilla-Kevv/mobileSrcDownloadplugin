package com.clipdown.downloader.model

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 下载文件的「当前是否真的可读」判定。
 *
 * 背景（阶段 84）：历史记录里的条目必须**独立于文件存在性**长期保留——
 * 用户把相册里的文件隐藏 / 移动到别的文件夹 / 删除之后，记录本身不能消失，
 * 但也不能再让用户点开一个空壳预览。所以展示层需要区分三种情况：
 *
 * 1. [Available]：文件确实还能读到 → 保留全部原有能力（预览 / 分享 / 外部打开）
 * 2. [Missing]：记录在、存储位置已知，但内容已不可读 → 显示缩略图 + 「文件不可用」标记，禁用预览
 * 3. [Unknown]：连存储位置都没记录（极旧数据）→ 按 [Missing] 处理
 *
 * 判定不看数据库里的 status 字段（那是下载过程的状态，不代表文件仍存在），
 * 一律**实际探测**存储：`content://` 走 ContentResolver，普通路径走 `File.exists()`。
 */
enum class FileAvailability {
    /** 文件存在且可读 */
    Available,

    /** 文件已丢失（被隐藏 / 移动 / 删除），但历史记录保留 */
    Missing,

    /** 没有任何可用的存储位置记录 */
    Unknown;

    val isPlayable: Boolean get() = this == Available

    val label: String
        get() = when (this) {
            Available -> "可预览"
            Missing -> "文件不可用"
            Unknown -> "内容已丢失"
        }
}

object FileAvailabilityChecker {

    /** 缓存：列表滚动时同一路径不必每次都打磁盘 */
    private val cache = object : LinkedHashMap<String, Pair<Long, FileAvailability>>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Long, FileAvailability>>
        ): Boolean = size > 256
    }

    private const val CACHE_TTL_MS = 3_000L

    /**
     * 探测任务对应的文件是否仍可读。
     *
     * @param localUri 相册/MediaStore 写入后返回的 content:// Uri（存在时优先用它）
     * @param localPath 私有目录或公共目录的绝对路径（升级前的老任务可能只有它）
     */
    fun check(
        context: Context,
        localUri: String?,
        localPath: String?,
        useCache: Boolean = true
    ): FileAvailability {
        val key = localUri ?: localPath
        if (key.isNullOrBlank()) return FileAvailability.Unknown

        val now = System.currentTimeMillis()
        if (useCache) {
            cache[key]?.let { (at, value) ->
                if (now - at < CACHE_TTL_MS) return value
            }
        }

        val result = probe(context, localUri, localPath)
        cache[key] = now to result
        return result
    }

    private fun probe(context: Context, localUri: String?, localPath: String?): FileAvailability {
        if (localUri.isNullOrBlank() && localPath.isNullOrBlank()) return FileAvailability.Unknown

        // 1. content:// 优先：相册里被隐藏或移走后，openAssetFileDescriptor 会抛异常
        if (!localUri.isNullOrBlank()) {
            val uri = runCatching { Uri.parse(localUri) }.getOrNull()
            if (uri != null && uri.scheme == "content") {
                val readable = runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull() != null
                if (readable) return FileAvailability.Available
                // content 不可读时继续用路径兜底（有些机型 Uri 失效但文件仍在）
            }
        }

        // 2. 普通路径探测
        if (!localPath.isNullOrBlank()) {
            val f = File(localPath)
            if (f.exists() && f.length() > 0) return FileAvailability.Available
        }

        // 3. 有记录但都读不到 → 明确判为丢失
        return FileAvailability.Missing
    }

    /** 文件被用户重新放回后，清掉缓存让 UI 立刻恢复可预览 */
    fun invalidate() = cache.clear()
}

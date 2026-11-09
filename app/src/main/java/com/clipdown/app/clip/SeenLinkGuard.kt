package com.clipdown.app.clip

import android.util.Log
import com.clipdown.downloader.DownloadController
import com.clipdown.downloader.model.DownloadStatus
import com.clipdown.parser.core.UrlUtil

/**
 * 「这条链接是不是已经见过 / 已经下载过」的唯一判定入口。
 *
 * 阶段 82 抽出：此前首页（两处）与悬浮窗各抄了一份近乎逐字节相同的
 * `seenBefore / hasCompleted / dbSeen → toast` 逻辑，三份实现容易漂移——
 * 只要有一处忘了接 `dedupKey`，就会出现「几天前解析过却不弹提示」的假修复。
 *
 * 三个数据源按可信度排序：
 * 1. [SettingsRepository.seenLinks] —— 内存快照（旧通道，有 400 条上限、会减半丢弃）
 * 2. `tasks` 表 —— 已完成任务（真实下载过）
 * 3. `seen_links` 表 —— 长期持久化（无上限，按 `UrlUtil.dedupKey` 比对）
 *
 * 前两者保留是为了兼容升级前的历史；新写入一律同时落 (1) 与 (3)。
 */
object SeenLinkGuard {

    /**
     * 判定链接是否已被识别/下载过。
     *
     * @param url 待判定的链接（原始形态即可，内部会走 dedupKey）
     * @param seenSnapshot 调用方持有的 `settings.seenLinks` 快照；传 null 表示跳过该源
     * @param completedTasks 已完成任务列表；传 null 时从仓库实时读取
     * @param canonicalUrl 解析产物里的规范链接（可选），可提升命中率
     */
    fun isSeen(
        url: String,
        seenSnapshot: Set<String>? = null,
        completedTasks: List<com.clipdown.downloader.model.TaskEntity>? = null,
        canonicalUrl: String? = null
    ): Boolean {
        if (url.isBlank()) return false

        // 1. 内存快照（旧通道）：逐条 dedupKey 比对，避免 token 轮换导致漏判
        if (seenSnapshot != null) {
            val key = UrlUtil.dedupKey(url)
            val hit = seenSnapshot.any { UrlUtil.dedupKey(it) == key }
            if (hit) return true
        }

        // 2. 已完成任务
        val tasks = completedTasks ?: runCatching {
            DownloadController.repository().tasks.value
        }.getOrNull()
        if (tasks != null) {
            val key = UrlUtil.dedupKey(url)
            val hit = tasks.any { t ->
                t.status == DownloadStatus.COMPLETED && (
                    UrlUtil.dedupKey(t.url) == key ||
                        t.sourceUrl?.let { UrlUtil.dedupKey(it) == key } == true
                    )
            }
            if (hit) return true
        }

        // 3. 长期持久化表
        return runCatching {
            DownloadController.database().isLinkSeen(url, canonicalUrl)
        }.onFailure {
            // 坑 12：database() 未初始化时会抛，这里降级为「未见过」但必须留痕
            Log.w("SeenLinkGuard", "查询已解析历史失败，按未见过处理: url=$url", it)
        }.getOrDefault(false)
    }

    /**
     * 记录一次解析/识别，写入两个通道。
     *
     * 必须在**解析成功后**调用并传入 `canonicalUrl`，否则只能记到短链形态，
     * 用户下次复制长链时仍可能漏判。
     *
     * suspend：内存快照走 DataStore，本身就是挂起操作；调用方一般已在 IO 协程里。
     */
    suspend fun markSeen(url: String, canonicalUrl: String? = null, settings: SettingsWriter?) {
        if (url.isBlank()) return
        runCatching {
            DownloadController.database().markLinkSeen(url, canonicalUrl)
        }.onFailure {
            Log.w("SeenLinkGuard", "写入长期已解析历史失败: url=$url", it)
        }
        settings?.let {
            runCatching { it.markLinkSeen(url) }
                .onFailure { e -> Log.w("SeenLinkGuard", "写入内存已解析快照失败: url=$url", e) }
        }
    }

    /** 只暴露「记一条」这一个能力，避免调用方把整个 SettingsRepository 拖进来 */
    fun interface SettingsWriter {
        suspend fun markLinkSeen(url: String)
    }
}

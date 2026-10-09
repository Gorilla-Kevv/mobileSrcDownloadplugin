package com.clipdown.app.floatwindow

import com.clipdown.app.clip.DetectedLink
import com.clipdown.downloader.model.DownloadStatus
import com.clipdown.parser.model.MediaItem
import com.clipdown.parser.model.ParseResult

/** 悬浮弹窗的 UI 状态 */
sealed interface PopupUiState {

    /** 不展示（默认） */
    data object Hidden : PopupUiState

    /**
     * 迷你面板：点击气泡后展开，仅提供"识别链接"入口，不自动解析。
     * confirmLink 非空时为"已解析过"提醒态：按钮变为"再次解析"，直接重跑该链接。
     */
    data class Mini(
        val recognizing: Boolean = false,
        val hint: String? = null,
        val confirmLink: DetectedLink? = null
    ) : PopupUiState

    /** 已捕获链接，正在解析 */
    data class Loading(val link: DetectedLink) : PopupUiState

    /** 解析成功，等待用户选择资源并下载；multiSelect=true（图集/多资源）时 chips 可多选 */
    data class Ready(
        val link: DetectedLink,
        val result: ParseResult,
        val selectedIndices: Set<Int> = setOf(0),
        val multiSelect: Boolean = false,
        val downloading: Boolean = false,
        val downloadPercent: Int? = null,
        val downloadDone: Boolean = false,
        val downloadError: String? = null
    ) : PopupUiState {
        val selected: MediaItem? get() = result.media.getOrNull(selectedIndices.firstOrNull() ?: -1)
    }

    /** 解析失败，展示原因与补救入口 */
    data class Failed(val link: DetectedLink, val message: String, val retryable: Boolean = true) : PopupUiState

    /**
     * 识别到博主主页：主页是"1 作者 + N 笔记"的集合形态，不能在气泡里逐篇下载，
     * 因此这里只做"确认 + 入口"，点「打开主页」进独立页面（[com.clipdown.app.ui.profile.ProfileScreen]）。
     */
    data class ProfileReady(
        val link: DetectedLink,
        val nickname: String,
        val postCount: Int,
        val platformName: String,
        val warning: String? = null
    ) : PopupUiState

    /**
     * 多功能面板（阶段 46）：长按气泡呼出，与竖条同一 64dp 窗口。
     * 呈现多路并发下载进度（按 taskId 分行 + 聚合进度条），并收口"关闭气泡/清空已完成"。
     * 已完成/失败行保留在列表内，直到用户点「清空已完成」或服务重启。
     */
    data class Panel(
        val tasks: List<DownloadRow>,
        /** Σ已下载字节 / Σ总字节；总字节未知时为 null（不确定进度） */
        val aggregatePercent: Int?
    ) : PopupUiState
}

/** 自动下载流水线的一条任务行（面板按 taskId 索引，进度随事件流实时刷新） */
data class DownloadRow(
    val taskId: String,
    val title: String,
    val platformName: String,
    val platformId: String,
    val kindLabel: String,
    val percent: Int?,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val sizeText: String? = null,
    val error: String? = null
)

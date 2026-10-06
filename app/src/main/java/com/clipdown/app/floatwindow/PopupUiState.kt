package com.clipdown.app.floatwindow

import com.clipdown.app.clip.DetectedLink
import com.clipdown.parser.model.MediaItem
import com.clipdown.parser.model.ParseResult

/** 悬浮弹窗的 UI 状态 */
sealed interface PopupUiState {

    /** 不展示（默认） */
    data object Hidden : PopupUiState

    /** 迷你面板：点击气泡后展开，仅提供"识别链接"入口，不自动解析 */
    data class Mini(
        val recognizing: Boolean = false,
        val hint: String? = null
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

    /** 自动下载详情小卡：下载中单击气泡展示，进度随事件实时刷新 */
    data class Downloads(val tasks: List<DownloadRow>) : PopupUiState
}

/** 自动下载流水线的一条任务行 */
data class DownloadRow(
    val taskId: String,
    val title: String,
    val platformName: String,
    val percent: Int?,
    val sizeText: String? = null
)

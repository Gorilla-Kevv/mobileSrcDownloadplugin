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

    /** 解析成功，等待用户选择清晰度并下载 */
    data class Ready(
        val link: DetectedLink,
        val result: ParseResult,
        val selectedIndex: Int = 0,
        val downloading: Boolean = false,
        val downloadPercent: Int? = null,
        val downloadDone: Boolean = false,
        val downloadError: String? = null
    ) : PopupUiState {
        val selected: MediaItem? get() = result.media.getOrNull(selectedIndex)
    }

    /** 解析失败，展示原因与补救入口 */
    data class Failed(val link: DetectedLink, val message: String, val retryable: Boolean = true) : PopupUiState

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

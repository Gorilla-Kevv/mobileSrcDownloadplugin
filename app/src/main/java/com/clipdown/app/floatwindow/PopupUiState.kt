package com.clipdown.app.floatwindow

import com.clipdown.app.clip.DetectedLink
import com.clipdown.parser.model.MediaItem
import com.clipdown.parser.model.ParseResult

/** 悬浮弹窗的 UI 状态 */
sealed interface PopupUiState {

    /** 不展示（默认） */
    data object Hidden : PopupUiState

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
}

package com.clipdown.app.clip

import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.model.ParseResult
import com.clipdown.parser.model.Platform
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** 链接来源通道，影响 UI 上的来源标注与统计 */
enum class LinkSource(val label: String) {
    FOREGROUND_CLIP("前台剪贴板"),
    GATE_CLIP("借道读取"),
    ACCESSIBILITY("无障碍监听"),
    SHARE("系统分享"),
    MANUAL("手动粘贴")
}

/** 一条被识别出的候选链接 */
data class DetectedLink(
    val url: String,
    val platform: Platform,
    val source: LinkSource,
    val rawText: String = "",
    val detectedAtMillis: Long = System.currentTimeMillis()
)

/**
 * 链接中枢：所有通道（剪贴板 / 无障碍 / 分享 / 手动）统一汇聚到这里，
 * 再由悬浮窗、主页等订阅方消费。
 *
 * 同时承担去重职责：同一条链接在 [DEDUP_WINDOW_MS] 内只触发一次，
 * 避免用户在 Instagram 里反复复制链接导致弹窗刷屏。
 */
object LinkCenter {

    private val _detected = MutableSharedFlow<DetectedLink>(replay = 1, extraBufferCapacity = 8)
    val detected: SharedFlow<DetectedLink> = _detected.asSharedFlow()

    private val _last = MutableStateFlow<DetectedLink?>(null)
    val last: StateFlow<DetectedLink?> = _last.asStateFlow()

    /** 最近一次解析结果，悬浮窗与主页共用，避免重复解析 */
    private val _lastResult = MutableStateFlow<ParseResult?>(null)
    val lastResult: StateFlow<ParseResult?> = _lastResult.asStateFlow()

    /**
     * 提交一段文本。
     *
     * @return 命中的候选链接；文本中没有受支持链接时返回 null
     */
    fun submit(text: String?, source: LinkSource, force: Boolean = false): DetectedLink? {
        val pair = ParserEngine.quickDetect(text) ?: return null
        return submitUrl(pair.first, pair.second, source, text ?: "", force)
    }

    fun submitUrl(
        url: String,
        platform: Platform,
        source: LinkSource,
        rawText: String = "",
        force: Boolean = false
    ): DetectedLink? {
        val previous = _last.value
        if (!force && previous != null &&
            previous.url == url &&
            System.currentTimeMillis() - previous.detectedAtMillis < DEDUP_WINDOW_MS
        ) {
            return null
        }
        val link = DetectedLink(url, platform, source, rawText)
        _last.value = link
        _detected.tryEmit(link)
        return link
    }

    /** 解析结果回填 */
    fun publishResult(result: ParseResult) {
        _lastResult.value = result
    }

    fun clear() {
        _last.value = null
        _lastResult.value = null
    }

    const val DEDUP_WINDOW_MS = 15_000L
}

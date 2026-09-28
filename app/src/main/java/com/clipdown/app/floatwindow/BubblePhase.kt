package com.clipdown.app.floatwindow

/**
 * 气泡状态机相位：驱动 [FloatingWindowService] 的 BubbleContent 光环动效与描边颜色。
 *
 * 纯 Kotlin 定义（无 Compose 依赖），视觉参数映射在服务层。
 * 瞬态相位（ParseOk/ParseFail/DownloadOk/DownloadFail）由服务层 setPhase 调度到点自动回 [Idle]；
 * [Parsing] 与 [Downloading] 是持续相位，必须由下一次 setPhase 或显式回退离开，否则会卡死。
 */
sealed interface BubblePhase {

    /** 空闲：蓝色呼吸光圈 */
    data object Idle : BubblePhase

    /** 解析中：黄色旋转光圈 */
    data object Parsing : BubblePhase

    /** 解析成功：绿色闪现，短暂后回空闲 */
    data object ParseOk : BubblePhase

    /** 解析失败：红色闪现，短暂后回空闲 */
    data object ParseFail : BubblePhase

    /** 下载中：环形进度；percent 为 null 表示进度不确定（合并音视频阶段） */
    data class Downloading(val percent: Int? = null) : BubblePhase

    /** 下载成功：紫色闪现，短暂后回空闲 */
    data object DownloadOk : BubblePhase

    /** 下载失败：红色闪现，短暂后回空闲 */
    data object DownloadFail : BubblePhase
}

package com.clipdown.app.floatwindow

import android.content.res.Resources
import kotlin.math.roundToInt

/**
 * 悬浮球窗口的几何计算。
 *
 * 阶段 82 抽出：`((64 - BUBBLE_LOGO_SIZE_DP) / 2f * dm.density).toInt()` 这段
 * 在窗口创建、拖拽吸附、`syncBubbleWindow` 三处逐字重复，还夹杂裸 `64 * density`。
 * 一旦其中一个常量改动而漏改另一处，就会出现「气泡贴边忽有忽无间隙」这类
 * 只在某个方向出现的怪现象——所以把它们收进一个类型，只算一次。
 *
 * 坐标语义：`params.x/y` 描述的是 **64dp 触摸外壳** 的左上角。
 * 外壳内居中的 52dp Logo 两侧各有 [visualMarginPx]/2 的透明留白，
 * 因此要让 Logo 真正贴住屏幕边框，外壳必须允许负偏移，即 [minX]。
 *
 * 构造参数用原始数值而非 `Resources`，这样纯 JVM 单测可以直接验证贴边数学
 * （`Resources` 在普通单测里构造不出来，会把这段最容易写错的几何挡在测试之外）。
 */
class BubbleLayout(
    val density: Float,
    val screenWidthPx: Int,
    val screenHeightPx: Int
) {

    constructor(resources: Resources) : this(
        density = resources.displayMetrics.density,
        screenWidthPx = resources.displayMetrics.widthPixels,
        screenHeightPx = resources.displayMetrics.heightPixels
    )

    /** 触摸外壳边长（dp→px） */
    val shellSizePx: Int = (SHELL_DP * density).roundToInt()

    /**
     * 外壳与可见 Logo 之间的单侧透明留白（px）。
     * = (shell - logo) / 2，四舍五入到像素。
     */
    val visualMarginPx: Int = ((SHELL_DP - BUBBLE_LOGO_SIZE_DP) / 2f * density).roundToInt()

    /** 拖拽/吸附的左边界：负偏移让 Logo 外缘与屏幕左缘重合 */
    val minX: Int get() = -visualMarginPx

    /** 拖拽/吸附的右边界：同理允许外壳右侧溢出屏幕 */
    val maxX: Int get() = screenWidthPx - shellSizePx + visualMarginPx

    /** 右侧初始停靠位 */
    val defaultX: Int get() = maxX

    /** 吸附触发距离 */
    val snapThresholdPx: Int = (SNAP_THRESHOLD_DP * density).roundToInt()

    /** 把任意 x 夹到合法区间 */
    fun clampX(x: Int): Int = x.coerceIn(minX, maxX)

    /** 松手时吸边：靠近哪侧就贴到哪侧，否则保持原位 */
    fun snapX(x: Int): Int {
        val clamped = clampX(x)
        return when {
            clamped <= minX + snapThresholdPx -> minX
            clamped >= maxX - snapThresholdPx -> maxX
            else -> clamped
        }
    }

    /** 给定 y，判断竖条应向上还是向下延伸（中线以上向下） */
    fun barGrowsDown(y: Int): Boolean = y < screenHeightPx / 2

    private companion object {
        /** 触摸外壳（含透明留白）边长 */
        const val SHELL_DP = 64f

        /** 吸边触发距离：距边缘 40dp 内松手即贴边 */
        const val SNAP_THRESHOLD_DP = 40f
    }
}

/**
 * 可见 Logo 直径（dp）。外壳 [64dp] 与它之间的差值就是两侧的透明留白，
 * 气泡绘制、光环半径、Compose 布局都以此为准——**只在这里定义一次**。
 */
internal const val BUBBLE_LOGO_SIZE_DP = 52f

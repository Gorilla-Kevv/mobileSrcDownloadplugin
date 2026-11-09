package com.clipdown.app.floatwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定悬浮球贴边几何。
 *
 * 背景：这段数学曾直接写在 `FloatingWindowService` 的三个位置，其中一处漏改
 * 就会表现为「气泡贴边时仍隔着一小段间隙」，且只在某个方向复现，极难定位。
 * 抽成 [BubbleLayout] 后在这里把「贴边 = 视觉 0 间隙」钉死。
 */
class BubbleLayoutTest {

    /** 3x 密度：64dp 外壳 = 192px，52dp Logo = 156px，单侧留白 = 18px */
    private val layout = BubbleLayout(density = 3f, screenWidthPx = 1080, screenHeightPx = 2400)

    @Test
    fun `外壳与可见留白按密度换算`() {
        assertEquals(192, layout.shellSizePx)
        assertEquals(18, layout.visualMarginPx)
    }

    /**
     * 核心不变量：把 x 夹到 [minX] 时，Logo 的左外缘恰好落在屏幕 x = 0。
     * 外壳左缘在 -18px，Logo 相对外壳居中（左右各 18px），因此 Logo 左缘 = -18 + 18 = 0。
     */
    @Test
    fun `贴左边缘时 Logo 外缘与屏幕重合 无间隙`() {
        val bubbleLeft = layout.minX + layout.visualMarginPx
        assertEquals("Logo 左缘必须恰好是屏幕 x=0", 0, bubbleLeft)
        assertEquals(-18, layout.minX)
    }

    /** 对称性：贴右边缘时 Logo 右缘恰好等于屏幕宽度 */
    @Test
    fun `贴右边缘时 Logo 外缘与屏幕右缘重合`() {
        val logoRight = layout.maxX + layout.shellSizePx - layout.visualMarginPx
        assertEquals("Logo 右缘必须等于屏宽", layout.screenWidthPx, logoRight)
    }

    @Test
    fun `clampX 允许负偏移但不越过贴边极值`() {
        assertEquals(layout.minX, layout.clampX(-999))
        assertEquals(layout.maxX, layout.clampX(99_999))
        // 中间值原样返回
        assertEquals(400, layout.clampX(400))
    }

    @Test
    fun `snapX 在阈值内吸附到边缘 阈值外保持原位`() {
        // 距离左极值 30px（< 40dp*3=120px 阈值）→ 吸到左极值
        assertEquals(layout.minX, layout.snapX(layout.minX + 30))
        // 距离右极值 30px → 吸到右极值
        assertEquals(layout.maxX, layout.snapX(layout.maxX - 30))
        // 屏幕正中，两侧都远 → 不动
        val middle = layout.screenWidthPx / 2
        assertEquals(middle, layout.snapX(middle))
    }

    @Test
    fun `竖条延伸方向按屏幕中线判定`() {
        assertTrue("中线以上应向下延伸", layout.barGrowsDown(100))
        assertFalse("中线以下应向上延伸", layout.barGrowsDown(2000))
    }
}

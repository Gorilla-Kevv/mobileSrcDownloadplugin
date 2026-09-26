package com.clipdown.app.clip

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * 借道前台 Activity。
 *
 * Android 10+ 后台读不到剪贴板，因此这里用一个**完全透明、无动画、不进最近任务**的
 * Activity 把进程临时拉到前台，在 onResume 里读一次剪贴板，随后立即 finish。
 * 用户全程无感知（耗时约 100~300ms）。
 *
 * 触发时机：悬浮窗被点击、通知被点击、无障碍服务捕捉到"疑似复制"行为。
 */
class ClipGateActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不 setContentView：整窗透明
    }

    override fun onResume() {
        super.onResume()
        val text = ClipboardMonitor.readFromGate()
        if (!text.isNullOrBlank()) {
            LinkCenter.submit(text, LinkSource.GATE_CLIP)
        }
        // 交给悬浮窗弹窗展示，这里立刻退出前台
        handler.postDelayed({ finishAndRemoveTaskCompat() }, 120)
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }

    private fun finishAndRemoveTaskCompat() {
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }
}

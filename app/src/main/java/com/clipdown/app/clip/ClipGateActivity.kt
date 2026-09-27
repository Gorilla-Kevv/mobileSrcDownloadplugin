package com.clipdown.app.clip

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不 setContentView：整窗透明
    }

    /**
     * 必须等窗口真正拿到焦点再读剪贴板：Android 12+ 上 onResume 早于焦点授予，
     * 在 onResume 里读会被 ClipboardService 以 "not in focus" 拒绝（实测日志）。
     * 部分 ROM 焦点授予存在延迟，读取失败时短间隔重试。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        scope.launch {
            var text: String? = null
            repeat(3) {
                text = ClipboardMonitor.readFromGate()
                if (!text.isNullOrBlank()) return@repeat
                delay(200)
            }
            val hit = if (!text.isNullOrBlank()) {
                // force：借道读到的剪贴板始终视为用户意图，绕过 15 秒链接去重
                LinkCenter.submit(text, LinkSource.GATE_CLIP, force = true)
            } else null
            onResult?.invoke(hit != null)
            onResult = null
            // 交给悬浮窗弹窗展示，这里立刻退出前台
            handler.postDelayed({ finishAndRemoveTaskCompat() }, 120)
        }
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }

    private fun finishAndRemoveTaskCompat() {
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    companion object {
        /** 识别结果回调（悬浮窗迷你面板使用）：参数 = 剪贴板里是否命中受支持链接 */
        @Volatile
        var onResult: ((Boolean) -> Unit)? = null
    }
}

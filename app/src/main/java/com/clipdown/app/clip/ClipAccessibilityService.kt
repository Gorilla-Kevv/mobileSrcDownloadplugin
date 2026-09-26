package com.clipdown.app.clip

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.clipdown.app.floatwindow.FloatingWindowService
import com.clipdown.parser.core.ParserEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 无障碍监听通道。
 *
 * 为什么需要它：Android 10 之后后台应用读不到剪贴板，而"在 Instagram 里复制了链接"
 * 恰恰发生在后台场景。无障碍服务可以拿到**当前窗口的可见文本**，
 * 于是我们从两类事件里提取链接：
 * 1. `TYPE_WINDOW_CONTENT_CHANGED` / `TYPE_WINDOW_STATE_CHANGED`：扫描窗口中的文本节点，
 *    命中受支持的链接即提示（覆盖"复制分享文案"的场景）；
 * 2. 浏览器地址栏（Chrome / Via 等）本质也是文本节点，可顺带识别当前浏览的页面链接。
 *
 * 隐私承诺：只在本地做正则匹配，命中的链接交给本地解析内核，不上传任何窗口内容。
 */
class ClipAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onServiceConnected() {
        super.onServiceConnected()
        FloatingWindowService.tryStart(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.packageName == packageName) return

        when (ev.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> Unit
            else -> return
        }

        val text = collectText(rootInActiveWindow) ?: return
        if (text.length > MAX_SCAN) return

        scope.launch {
            val hit = ParserEngine.quickDetect(text) ?: return@launch
            LinkCenter.submitUrl(
                url = hit.first,
                platform = hit.second,
                source = LinkSource.ACCESSIBILITY,
                rawText = text.take(200)
            )
        }
    }

    override fun onInterrupt() = Unit

    private fun collectText(node: android.view.accessibility.AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val sb = StringBuilder()
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH) return
            n.text?.let { sb.append(it).append(' ') }
            n.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        return runCatching {
            walk(node, 0)
            sb.toString().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    companion object {
        private const val MAX_DEPTH = 12
        private const val MAX_SCAN = 4000
    }
}

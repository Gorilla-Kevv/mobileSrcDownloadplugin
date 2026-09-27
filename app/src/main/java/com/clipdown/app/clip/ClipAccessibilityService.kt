package com.clipdown.app.clip

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.clipdown.app.floatwindow.FloatingWindowService
import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 无障碍监听通道——"复制链接后自动弹窗"的主触发器。
 *
 * Android 10 之后后台读剪贴板被系统拒绝（无障碍服务同样受限，实测
 * ClipboardService 会报 "Denying clipboard access"），因此采用三段式策略：
 *
 * 1. **复制特征借道**：App 复制链接后普遍会弹 Toast（"链接已复制"/"Copied"），
 *    以 `TYPE_NOTIFICATION_STATE_CHANGED` 捕捉该特征，随即启动透明的
 *    [ClipGateActivity] 借道前台读取剪贴板（无障碍 + 悬浮窗权限持有后台启动豁免），
 *    用户无感知。这是 Instagram / X 等 App 内复制场景的唯一可靠通路。
 * 2. **剪贴板直读**：借道瞬间或部分 ROM 上剪贴板可直接读取，顺手尝试。
 * 3. **窗口文本扫描**：链接直接出现在屏幕上的场景（浏览器地址栏、
 *    Chrome 复制后的链接气泡），逐节点扫描可见文本。
 *
 * 防抖采用**尾部合并**：事件风暴结束后统一扫描一次。早期版本用"丢弃式"防抖，
 * 复制瞬间第一条事件先于内容出现，携带真实数据的后续事件全被丢弃，导致弹窗不触发。
 *
 * 隐私承诺：只在本地做正则匹配，命中的链接交给本地解析内核，不上传任何窗口内容。
 */
class ClipAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val clipboard by lazy {
        getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }

    private var scanJob: Job? = null
    private var lastBorrowAt = 0L

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
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> Unit
            else -> return
        }

        // 通道一触发器：复制特征 Toast → 借道前台读剪贴板
        if (ev.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val hint = ev.text?.joinToString(" ") { it?.toString().orEmpty() }.orEmpty()
            if (COPY_HINT_REGEX.containsMatchIn(hint)) borrowForClipboard()
        }

        // 通道二/三：尾部合并扫描（最后一次事件后静默 SCAN_QUIET_MS 再执行）
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(SCAN_QUIET_MS)
            handleScan()
        }
    }

    override fun onInterrupt() = Unit

    /** 启动透明借道 Activity 读取剪贴板（带冷却，避免 Toast 连发时反复抢焦点） */
    private fun borrowForClipboard() {
        val now = System.currentTimeMillis()
        if (now - lastBorrowAt < BORROW_COOLDOWN_MS) return
        lastBorrowAt = now
        runCatching {
            startActivity(
                Intent(this, ClipGateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }

    private fun handleScan() {
        // 通道二：剪贴板直读（有前台焦点或宽松 ROM 时可行）
        runCatching {
            val clip = clipboard.primaryClip
                ?.getItemAt(0)?.coerceToText(this)?.toString()
            if (!clip.isNullOrBlank()) {
                ParserEngine.quickDetect(clip)?.let { hit ->
                    LinkCenter.submitUrl(
                        url = hit.first,
                        platform = hit.second,
                        source = LinkSource.ACCESSIBILITY,
                        rawText = clip.take(200)
                    )
                    return
                }
            }
        }

        // 通道三：窗口可见文本逐节点扫描（地址栏 / Chrome 复制气泡等场景）
        val root = rootInActiveWindow ?: return
        runCatching {
            val hit = walkForUrl(root, 0)
            if (hit != null) {
                LinkCenter.submitUrl(
                    url = hit.first,
                    platform = hit.second,
                    source = LinkSource.ACCESSIBILITY,
                    rawText = hit.first.take(200)
                )
                return
            }
            // 通道四：Instagram 等应用用 Snackbar（而非 Toast）提示"已复制"，
            // 不产生通知事件。检测窗口内的短促复制提示文本，命中即借道读剪贴板。
            if (walkHasCopyHint(root, 0)) borrowForClipboard()
        }
    }

    /** 深度优先扫描节点文本，命中受支持链接立即返回 */
    private fun walkForUrl(node: AccessibilityNodeInfo?, depth: Int): Pair<String, Platform>? {
        if (node == null || depth > MAX_DEPTH) return null
        val own = buildString {
            node.text?.let { append(it) }
            node.contentDescription?.let { append(' '); append(it) }
        }
        if (own.isNotBlank()) {
            val hit = ParserEngine.quickDetect(own)
            // 跳过短链：页面内容里的 t.co 等多为媒体跳转链接，展开后落在媒体主机，
            // 无法还原作品页；用户真正要的链接由地址栏/复制气泡以完整形态提供
            if (hit != null && !com.clipdown.parser.core.UrlUtil.isShortLink(hit.first)) return hit
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            walkForUrl(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** 是否存在短促的"已复制"提示节点（Snackbar/横幅，长度受限以避免误伤正文） */
    private fun walkHasCopyHint(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > MAX_DEPTH) return false
        val own = node.text?.toString().orEmpty()
        if (own.length <= COPY_HINT_MAX_LEN && COPY_HINT_REGEX.containsMatchIn(own)) return true
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            if (walkHasCopyHint(child, depth + 1)) return true
        }
        return false
    }

    companion object {
        private const val MAX_DEPTH = 12
        private const val SCAN_QUIET_MS = 350L
        private const val BORROW_COOLDOWN_MS = 5_000L
        private const val COPY_HINT_MAX_LEN = 24

        /** 复制特征文案：覆盖中文（链接已复制/已复制/复制成功）与英文（Copied/Link copied） */
        private val COPY_HINT_REGEX =
            Regex("(?i)(复制|拷贝|copied|copy link|copy to clipboard|link copied)")
    }
}

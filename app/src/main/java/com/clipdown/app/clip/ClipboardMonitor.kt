package com.clipdown.app.clip

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 剪贴板监听与读取。
 *
 * 关键现实约束：**Android 10 起，只有前台应用（或默认输入法）才能读取剪贴板**。
 * 因此本类把"监听"和"读取"解耦：
 * - 监听：`OnPrimaryClipChangedListener` 在任何情况下都会回调（回调本身不代表能读到内容）；
 * - 读取：只有在 App 处于前台时才能成功，其余情况返回 null 并交给其它通道兜底
 *   （见 [ClipGateActivity] 与 [ClipAccessibilityService]）。
 *
 * 这样做的收益：不需 Root、不依赖隐藏 API，同时在主流机型上保持可用。
 */
object ClipboardMonitor {

    private var app: Application? = null
    private var clipboard: ClipboardManager? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var foreground = false

    @Volatile
    private var lastRead: String? = null

    fun install(app: Application) {
        this.app = app
        this.clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        startListening()
    }

    /** Activity onResume 时调用：标记前台并立即尝试读取一次 */
    fun onForeground() {
        foreground = true
        readAndSubmit(LinkSource.FOREGROUND_CLIP)
    }

    fun onBackground() {
        foreground = false
    }

    fun startListening() {
        val cm = clipboard ?: return
        if (listener != null) return
        listener = ClipboardManager.OnPrimaryClipChangedListener {
            // 回调发生在主线程；读取必须在前台态，否则系统返回空
            mainHandler.post { readAndSubmit(LinkSource.FOREGROUND_CLIP) }
        }
        cm.addPrimaryClipChangedListener(listener)
    }

    fun stopListening() {
        val cm = clipboard ?: return
        listener?.let { cm.removePrimaryClipChangedListener(it) }
        listener = null
    }

    /**
     * 读取当前剪贴板并提交到 [LinkCenter]。
     *
     * @return 读到的文本；受限时返回 null
     */
    fun readAndSubmit(source: LinkSource): String? {
        val text = readText() ?: return null
        if (text == lastRead) return text
        lastRead = text
        LinkCenter.submit(text, source)
        return text
    }

    /** 强制读取（用于"手动解析"按钮）：忽略去重 */
    fun readAndSubmitForce(): String? {
        val text = readText() ?: return null
        lastRead = text
        return LinkCenter.submit(text, LinkSource.MANUAL, force = true)?.let { text }
    }

    /** 读取剪贴板文本；Android 10+ 非前台场景会返回 null 或空串 */
    fun readText(): String? {
        val cm = clipboard ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !foreground) {
            // 后台受限：直接放弃，交由借道 / 无障碍通道处理，避免无意义的异常
            return null
        }
        return runCatching {
            val clip: ClipData? = cm.primaryClip
            if (clip == null || clip.itemCount == 0) null
            else clip.getItemAt(0)?.coerceToText(app)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 借道读取：由透明 Activity 在 onResume 中调用，此时进程处于前台 */
    fun readFromGate(): String? {
        foreground = true
        return readText()
    }
}

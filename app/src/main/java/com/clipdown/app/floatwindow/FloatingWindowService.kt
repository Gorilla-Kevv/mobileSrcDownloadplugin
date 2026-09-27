package com.clipdown.app.floatwindow

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import com.clipdown.app.clip.ClipGateActivity
import com.clipdown.app.clip.LinkCenter
import com.clipdown.app.data.SettingsRepository
import com.clipdown.app.ui.theme.ClipDownTheme
import com.clipdown.app.ui.theme.SeedBlue
import com.clipdown.downloader.DownloadController
import com.clipdown.downloader.DownloadService
import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.model.ParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 悬浮窗服务：应用的主交互形态。
 *
 * 组成：
 * - 悬浮球：常驻屏幕边缘，可拖动，点击后"借道"读取剪贴板并立即解析；
 * - 毛玻璃弹窗：解析成功后半屏居中弹出，展示封面、清晰度选项与下载入口，若干秒后自动收起。
 *
 * 视觉实现：Android 12+ 使用系统级 `FLAG_BLUR_BEHIND` + `setBackgroundBlurRadius` 得到真实背景模糊；
 * 低版本退化为半透明遮罩（见 [ClipPopupContent]）。
 */
class FloatingWindowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var popupHost: View? = null

    private var bubbleLifecycle: OverlayLifecycleOwner? = null
    private var popupLifecycle: OverlayLifecycleOwner? = null

    private val uiState: MutableState<PopupUiState> = mutableStateOf(PopupUiState.Hidden)
    private val remainSeconds = mutableIntStateOf(0)
    private val hasPending = mutableStateOf(false)

    private var dismissJob: Job? = null
    private var parseJob: Job? = null
    private var downloadJob: Job? = null
    private var recognizeTimeoutJob: Job? = null

    /** 手动识别模式：迷你面板点"识别链接"后置位，下一个链接无条件弹窗 */
    @Volatile
    private var manualRecognize = false

    /** 下载进行中：期间任何新链接都不允许打断弹窗（视频播放会持续触发窗口扫描） */
    @Volatile
    private var downloadActive = false

    /** 下载完成后短时抑制同一链接的自动重弹（地址栏仍在该页面时会持续触发扫描） */
    private var suppressedUrl: String? = null
    private var suppressedAt = 0L

    private val settings by lazy { SettingsRepository(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundCompat()
        addBubble()
        observeLinks()
        scope.launch {
            settings.autoPopup.collect { autoPopupEnabled = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE_POPUP -> hidePopup()
            ACTION_SHOW_LAST -> LinkCenter.last.value?.let { showAndParse(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        dismissJob?.cancel()
        parseJob?.cancel()
        downloadJob?.cancel()
        recognizeTimeoutJob?.cancel()
        removePopup()
        removeBubble()
        super.onDestroy()
    }

    // ---------- 悬浮球 ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val lifecycleOwner = OverlayLifecycleOwner()
        bubbleLifecycle = lifecycleOwner

        val composeView = ComposeView(this).apply {
            setContent {
                ClipDownTheme(darkTheme = true) {
                    BubbleContent(hasPending.value)
                }
            }
        }
        bindOwners(composeView, lifecycleOwner)
        lifecycleOwner.attach()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (x, y) = lastPosition
            this.x = if (x >= 0) x else resources.displayMetrics.widthPixels - 140
            this.y = if (y >= 0) y else resources.displayMetrics.heightPixels / 3
        }

        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var moved = false

        @Suppress("DEPRECATION")
        composeView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { wm.updateViewLayout(composeView, params) }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        onBubbleClick()
                    } else {
                        scope.launch { settings.saveBubblePosition(params.x, params.y) }
                        lastPosition = params.x to params.y
                    }
                }
            }
            true
        }

        runCatching { wm.addView(composeView, params) }
        bubbleView = composeView
    }

    private fun onBubbleClick() {
        // 气泡是纯入口：只展开迷你面板，不读剪贴板、不解析、不跳应用
        uiState.value = PopupUiState.Mini()
        ensurePopupHost()
        // 迷你面板不自动收起，等用户操作或点空白回气泡
        dismissJob?.cancel()
        remainSeconds.value = 0
    }

    /** 迷你面板"识别链接"：借道前台读剪贴板并解析（绕过识别记忆） */
    private fun startManualRecognize() {
        uiState.value = PopupUiState.Mini(recognizing = true)
        manualRecognize = true
        ClipGateActivity.onResult = { found ->
            if (!found) {
                manualRecognize = false
                uiState.value = PopupUiState.Mini(hint = "剪贴板中没有可识别的链接")
            }
            // found 时由 LinkCenter.detected 接管，切到解析流程
        }
        startActivity(Intent(this, ClipGateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // 兜底：gate 未能获取焦点等异常场景下复位迷你面板
        recognizeTimeoutJob?.cancel()
        recognizeTimeoutJob = scope.launch {
            delay(4_000)
            if (manualRecognize && uiState.value is PopupUiState.Mini) {
                manualRecognize = false
                uiState.value = PopupUiState.Mini(hint = "未能读取剪贴板，请重试")
            }
        }
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleLifecycle?.detach()
        bubbleView = null
    }

    // ---------- 弹窗 ----------

    private fun observeLinks() {
        LinkCenter.detected
            .onEach { link ->
                hasPending.value = true
                // 识别记忆：检测过的链接持久化，自动弹窗只给"新面孔"
                val seenBefore = settings.seenLinks.first()
                scope.launch { settings.markLinkSeen(link.url) }

                if (manualRecognize) {
                    manualRecognize = false
                    recognizeTimeoutJob?.cancel()
                    showAndParse(link)
                    return@onEach
                }
                if (link.url in seenBefore) return@onEach
                if (autoPopupEnabled) showAndParse(link)
            }
            .launchIn(scope)
    }

    private fun showAndParse(link: com.clipdown.app.clip.DetectedLink) {
        if (downloadActive) {
            hasPending.value = true
            return
        }
        suppressedUrl?.let { url ->
            if (link.url == url && System.currentTimeMillis() - suppressedAt < SUPPRESS_MS) {
                hasPending.value = true
                return
            }
        }
        hasPending.value = false
        uiState.value = PopupUiState.Loading(link)
        ensurePopupHost()
        startDismissTimer()

        parseJob?.cancel()
        parseJob = scope.launch {
            val result = withContext(Dispatchers.IO) { ParserEngine.parseSafe(link.url) }
            val current = uiState.value
            if (current !is PopupUiState.Loading) return@launch

            if (result.isSuccess) {
                val parsed = result.getOrThrow()
                LinkCenter.publishResult(parsed)
                uiState.value = PopupUiState.Ready(link, parsed, selectedIndex = 0)
            } else {
                val e = result.exceptionOrNull()
                val msg = (e as? ParseException)?.message ?: e?.message ?: "解析失败"
                uiState.value = PopupUiState.Failed(link, msg, retryable = (e as? ParseException)?.retryable ?: true)
            }
            startDismissTimer()
        }
    }

    private fun ensurePopupHost() {
        if (popupHost != null) return
        val lifecycleOwner = OverlayLifecycleOwner()
        popupLifecycle = lifecycleOwner

        val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val view = ComposeView(this).apply {
            setContent {
                ClipDownTheme(darkTheme = true) {
                    ClipPopupContent(
                        state = uiState.value,
                        blurSupported = blurSupported,
                        remainSeconds = remainSeconds.value,
                        onRecognize = { startManualRecognize() },
                        onSelect = { index ->
                            val s = uiState.value
                            if (s is PopupUiState.Ready) {
                                uiState.value = s.copy(selectedIndex = index)
                                startDismissTimer()
                            }
                        },
                        onDownload = { downloadCurrent() },
                        onRetry = { LinkCenter.last.value?.let { showAndParse(it) } },
                        onOpenApp = { openApp() },
                        onDismiss = { hidePopup() }
                    )
                }
            }
        }
        bindOwners(view, lifecycleOwner)
        lifecycleOwner.attach()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                setBlurBehindRadius(BLUR_RADIUS_PX)
                setFitInsetsTypes(0)
            }
        }

        runCatching { wm.addView(view, params) }
        popupHost = view
    }

    private fun removePopup() {
        popupHost?.let { runCatching { wm.removeView(it) } }
        popupLifecycle?.detach()
        popupHost = null
    }

    private fun hidePopup() {
        uiState.value = PopupUiState.Hidden
        dismissJob?.cancel()
        removePopup()
    }

    private fun startDismissTimer() {
        dismissJob?.cancel()
        dismissJob = scope.launch {
            val totalMs = settings.popupDismissMs()
            if (totalMs <= 0) return@launch
            val totalSec = (totalMs / 1000).toInt()
            for (s in totalSec downTo 0) {
                remainSeconds.value = s
                delay(1_000)
                if (uiState.value is PopupUiState.Hidden) break
            }
            if (uiState.value !is PopupUiState.Hidden) hidePopup()
        }
    }

    private fun downloadCurrent() {
        val state = uiState.value
        if (state !is PopupUiState.Ready) return
        val item = state.selected ?: return
        // 下载中不自动收起弹窗：全部流程在弹窗内闭环；用户也可点空白收起，后台继续
        dismissJob?.cancel()
        remainSeconds.value = 0
        downloadActive = true
        hasPending.value = true
        uiState.value = state.copy(downloading = true, downloadPercent = 0)

        downloadJob?.cancel()
        downloadJob = scope.launch(Dispatchers.Main.immediate) {
            val taskId = withContext(Dispatchers.IO) {
                DownloadController.enqueue(item, state.result.platform, state.result.title)
            }
            startService(DownloadService.intent(this@FloatingWindowService, DownloadService.ACTION_RESUME))

            // 订阅进度：下载/合并/完成/失败全部在弹窗内呈现
            DownloadController.engine().progress.collect { e ->
                if (e.taskId != taskId) return@collect
                when (e.status) {
                    com.clipdown.downloader.model.DownloadStatus.COMPLETED,
                    com.clipdown.downloader.model.DownloadStatus.FAILED,
                    com.clipdown.downloader.model.DownloadStatus.CANCELED -> {
                        // 终态簿记与弹窗可见性无关：弹窗已收起时下载也在后台完成
                        downloadActive = false
                        if (e.status == com.clipdown.downloader.model.DownloadStatus.COMPLETED) {
                            suppressedUrl = state.link.url
                            suppressedAt = System.currentTimeMillis()
                        }
                        val cur = uiState.value
                        if (cur is PopupUiState.Ready && cur.link == state.link) {
                            when (e.status) {
                                com.clipdown.downloader.model.DownloadStatus.COMPLETED -> {
                                    uiState.value = cur.copy(downloadPercent = 100, downloadDone = true)
                                    scope.launch {
                                        delay(2500)
                                        hidePopup()
                                    }
                                }
                                com.clipdown.downloader.model.DownloadStatus.FAILED ->
                                    uiState.value = cur.copy(downloadError = "下载失败，可在应用内重试")
                                else -> Unit
                            }
                        }
                        hasPending.value = false
                        currentCoroutineContext()[Job]?.cancel()
                    }
                    com.clipdown.downloader.model.DownloadStatus.MERGING -> {
                        val cur = uiState.value
                        if (cur is PopupUiState.Ready && cur.link == state.link) {
                            uiState.value = cur.copy(downloadPercent = 99)
                        }
                    }
                    else -> {
                        val pct = if (e.totalBytes > 0) ((e.downloadedBytes * 100) / e.totalBytes).toInt() else null
                        val cur = uiState.value
                        if (cur is PopupUiState.Ready && cur.link == state.link && pct != null) {
                            uiState.value = cur.copy(downloadPercent = pct)
                        }
                    }
                }
            }
        }
    }

    private fun openApp() {
        startActivity(
            Intent(this, com.clipdown.app.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        hidePopup()
    }

    // ---------- 基础设施 ----------

    /**
     * 把自造的 Lifecycle / SavedState 宿主挂到 View 树上。
     *
     * 这里用反射调用 ViewTree*.set：
     * 这两个类是 AndroidX 的内部工具类，在不同版本间会在 `lifecycle-runtime` 与
     * `lifecycle-runtime-android` 之间迁移，直接静态引用会导致编译期依赖脆弱；
     * 反射调用可以保证只要运行时 classpath 里存在就能正常工作。
     */
    private fun bindOwners(view: View, owner: OverlayLifecycleOwner) {
        runCatching {
            val c = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner")
            c.getMethod("set", View::class.java, LifecycleOwner::class.java).invoke(null, view, owner)
        }
        runCatching {
            val c = Class.forName("androidx.savedstate.ViewTreeSavedStateRegistryOwner")
            c.getMethod("set", View::class.java, SavedStateRegistryOwner::class.java).invoke(null, view, owner)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private var autoPopupEnabled: Boolean = true

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MONITOR, "剪贴板守护", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                }
            )
        }
        val n = NotificationCompat.Builder(this, CHANNEL_MONITOR)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("剪存悬浮窗已开启")
            .setContentText("检测到支持的链接时将提示下载")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL_MONITOR = "clipdown_monitor"
        private const val NOTIFICATION_ID = 9002
        private const val BLUR_RADIUS_PX = 56
        private const val SUPPRESS_MS = 10 * 60_000L

        const val ACTION_HIDE_POPUP = "com.clipdown.app.action.HIDE_POPUP"
        const val ACTION_SHOW_LAST = "com.clipdown.app.action.SHOW_LAST"

        private var lastPosition: Pair<Int, Int> = -1 to -1

        fun hasPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        /** 已授权则直接启动悬浮窗服务 */
        fun tryStart(context: Context) {
            if (!hasPermission(context)) return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(Intent(context, FloatingWindowService::class.java))
                } else {
                    context.startService(Intent(context, FloatingWindowService::class.java))
                }
            }
        }

        /** 仅在用户开启过悬浮窗开关时启动：用于开机恢复 */
        fun tryStartIfEnabled(context: Context) {
            if (!hasPermission(context)) return
            val enabled: Boolean = try {
                kotlinx.coroutines.runBlocking { SettingsRepository(context).floatEnabled.first() }
            } catch (e: Throwable) {
                true
            }
            if (enabled) tryStart(context)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingWindowService::class.java)) }
        }
    }
}

@Composable
private fun BubbleContent(hasPending: Boolean) {
    Box(
        modifier = Modifier.size(56.dp),
        contentAlignment = Alignment.Center
    ) {
        // 头像 logo 气泡：待处理时绿环 + 右上角绿点
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color(0xFF141824))
                .border(
                    1.5.dp,
                    if (hasPending) Color(0xFF2EB872) else Color.White.copy(alpha = 0.35f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(com.clipdown.app.R.drawable.bubble_logo),
                contentDescription = "剪存",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (hasPending) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(14.dp)
                        .background(Color(0xFF2EB872), CircleShape)
                        .border(1.5.dp, Color(0xFF141824), CircleShape)
                )
            }
        }
    }
}

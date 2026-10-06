package com.clipdown.app.floatwindow

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import com.clipdown.app.clip.ClipGateActivity
import com.clipdown.app.clip.LinkCenter
import com.clipdown.app.data.SettingsRepository
import com.clipdown.app.ui.theme.ClipDownTheme
import com.clipdown.app.ui.profile.ProfileCenter
import com.clipdown.downloader.DownloadController
import com.clipdown.downloader.DownloadService
import com.clipdown.downloader.model.DownloadStatus
import com.clipdown.parser.core.LinkKind
import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.model.MediaItem
import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.ParseResult
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
 * - 悬浮球：常驻屏幕边缘，可拖动；单击 = 读取剪贴板并直接走自动流水线
 *   （解析→单资源自动下载，图集/失败弹卡），下载中单击 = 下载详情小卡，双击 = 跳主界面；
 * - 毛玻璃弹窗：图集选择卡 / 失败原因卡 / 下载详情卡，若干秒后自动收起。
 *
 * 视觉实现：Android 12+ 使用系统级 `FLAG_BLUR_BEHIND` + `setBackgroundBlurRadius` 得到真实背景模糊；
 * 低版本退化为半透明遮罩（见 [ClipPopupContent]）。
 */
class FloatingWindowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var popupHost: View? = null

    /** 气泡顶部位置的屏幕绝对坐标；addBubble 时初始化，拖拽时更新 */
    private var bubblePos: Pair<Int, Int> = -1 to -1

    /** 气泡窗口参数（竖条开合/延伸方向切换时由 syncBubbleWindow 更新 gravity/y） */
    private var bubbleParams: WindowManager.LayoutParams? = null

    /** 竖条是否在气泡上方（气泡靠屏幕下缘时 true）；驱动触摸分区与布局排列 */
    private val barOnTop = mutableStateOf(false)

    private var bubbleLifecycle: OverlayLifecycleOwner? = null
    private var popupLifecycle: OverlayLifecycleOwner? = null

    private val uiState: MutableState<PopupUiState> = mutableStateOf(PopupUiState.Hidden)
    private val remainSeconds = mutableIntStateOf(0)
    private val bubblePhase = mutableStateOf<BubblePhase>(BubblePhase.Idle)

    /** 被下载中/抑制窗口搁置、尚未处理的链接数（气泡左上角绿徽标） */
    private val pendingCount = mutableIntStateOf(0)

    /** 并发解析中的链接数（气泡左下角黄徽标，阶段 16） */
    private val parsingCount = mutableIntStateOf(0)

    /** 下载进行中的任务数（气泡右下角蓝徽标，阶段 16） */
    private val downloadingCount = mutableIntStateOf(0)

    private var dismissJob: Job? = null
    private var parseJob: Job? = null
    private var downloadJob: Job? = null
    private var recognizeTimeoutJob: Job? = null

    /** 瞬态相位（闪现类）的自动回 Idle 调度 */
    private var phaseRevertJob: Job? = null

    /** 单击延迟执行（等双击窗口过期）；双击到来即取消 */
    private var singleTapJob: Job? = null
    private var lastTapAt = 0L

    /** 手动识别模式：迷你面板点"识别链接"后置位，下一个链接无条件弹窗 */
    @Volatile
    private var manualRecognize = false

    /** 自动下载流水线进行中的任务：taskId → 展示行（全部在主线程读写） */
    private val autoTasks = LinkedHashMap<String, DownloadRow>()

    /** 自动路径的后台解析 Job 集（互不取消，弹窗语义让位给手动流程） */
    private val autoParseJobs = mutableSetOf<Job>()

    /** 正在解析/下载流水线中的链接：防窗口扫描在 seen_links 异步落库前重复触发解析 */
    private val pipelineUrls = mutableSetOf<String>()

    /** 下载完成后按 URL 短时抑制自动重弹（地址栏停留页面会持续触发扫描） */
    private val suppressedUrls = HashMap<String, Long>()

    /** 自动下载失败退避（IG 风控保险丝）：连续失败达阈值后暂停自动下载一段时间 */
    private var consecutiveFailures = 0
    private var autoDownloadSuspendedUntil = 0L

    private var autoPopupEnabled: Boolean = true
    private var autoDownloadEnabled: Boolean = true
    private var wifiOnlyEnabled: Boolean = false

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
        scope.launch {
            settings.autoDownload.collect { autoDownloadEnabled = it }
        }
        scope.launch {
            settings.wifiOnly.collect { wifiOnlyEnabled = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE_POPUP -> hidePopup()
            ACTION_SHOW_LAST -> LinkCenter.last.value?.let { showAndParse(it) }
            // 调试通道：adb 直接驱动气泡状态机（视觉验收用，不影响正常流程）
            ACTION_DEBUG_PHASE -> intent.getStringExtra(EXTRA_PHASE)?.let { name ->
                when (name) {
                    "parsing" -> setPhase(BubblePhase.Parsing)
                    "parse_ok" -> setPhase(BubblePhase.ParseOk, revertMs = 1_200)
                    "parse_fail" -> setPhase(BubblePhase.ParseFail, revertMs = 4_000)
                    "downloading" -> setPhase(
                        BubblePhase.Downloading(
                            if (intent.hasExtra(EXTRA_PERCENT)) intent.getIntExtra(EXTRA_PERCENT, 0) else null
                        )
                    )
                    "download_ok" -> setPhase(BubblePhase.DownloadOk, revertMs = 2_000)
                    "download_fail" -> setPhase(BubblePhase.DownloadFail, revertMs = 6_000)
                    "idle" -> setPhase(BubblePhase.Idle)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        dismissJob?.cancel()
        parseJob?.cancel()
        downloadJob?.cancel()
        recognizeTimeoutJob?.cancel()
        phaseRevertJob?.cancel()
        singleTapJob?.cancel()
        autoParseJobs.forEach { it.cancel() }
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
                    BubbleWindowContent(
                        phase = bubblePhase.value,
                        pendingCount = pendingCount.intValue,
                        parsingCount = parsingCount.intValue,
                        downloadingCount = downloadingCount.intValue,
                        barState = uiState.value.takeIf {
                            it is PopupUiState.Mini || (it is PopupUiState.Ready && it.multiSelect)
                        },
                        barOnTop = barOnTop.value,
                        onRecognize = { startManualRecognize() },
                        onSelect = { index ->
                            val s = uiState.value
                            if (s is PopupUiState.Ready) {
                                uiState.value = s.copy(
                                    selectedIndices = if (index in s.selectedIndices) {
                                        s.selectedIndices - index
                                    } else {
                                        s.selectedIndices + index
                                    }
                                )
                                startDismissTimer()
                            }
                        },
                        onDownload = { downloadCurrent() },
                        onToggle = { onBubbleClick() },
                        onBarStateChanged = { syncBubbleWindow(it ?: PopupUiState.Hidden) }
                    )
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
        bubbleParams = params
        bubblePos = params.x to params.y

        var startX = 0
        var startTopY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var moved = false
        var gestureInBubble = false
        val bubblePartH = (64 * resources.displayMetrics.density).toInt()

        @Suppress("DEPRECATION")
        composeView.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // 触摸分区：气泡圆区域（64dp）处理拖拽/点击；竖条延伸区透传给 Compose 控件
                gestureInBubble = if (barOnTop.value) event.y >= v.height - bubblePartH else event.y <= bubblePartH
                if (!gestureInBubble) return@setOnTouchListener false
                startX = params.x
                startTopY = bubblePos.second
                startTouchX = event.rawX
                startTouchY = event.rawY
                moved = false
                return@setOnTouchListener true
            }
            if (!gestureInBubble) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    bubblePos = startX + dx to startTopY + dy
                    params.x = bubblePos.first
                    syncBubbleWindow(uiState.value)
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // 单击/双击区分：单击延迟到双击窗口过期后执行，双击到来即取消。
                        // 手势必须在 View 监听器里做——Compose 内容一旦加 pointerInput 就会
                        // 认领事件，View 级拖拽监听器收不到 DOWN，拖拽直接失效
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt <= DOUBLE_TAP_MS) {
                            lastTapAt = 0L
                            singleTapJob?.cancel()
                            onBubbleDoubleClick()
                        } else {
                            lastTapAt = now
                            singleTapJob?.cancel()
                            singleTapJob = scope.launch {
                                delay(DOUBLE_TAP_MS)
                                onBubbleClick()
                            }
                        }
                    } else {
                        scope.launch { settings.saveBubblePosition(bubblePos.first, bubblePos.second) }
                        lastPosition = bubblePos
                    }
                }
            }
            true
        }

        runCatching { wm.addView(composeView, params) }
        bubbleView = composeView
    }

    /**
     * 修复 12：竖条与气泡同窗口一体化，这里只负责窗口锚点与延伸方向：
     * 气泡靠上边缘 → 竖条向下延伸（TOP 锚，向下生长）；靠下边缘 → 竖条向上延伸
     * （BOTTOM 锚，向上生长）；收起态恢复 TOP 锚原位。拖拽时实时重算（可跨中线翻转）。
     */
    private fun syncBubbleWindow(state: PopupUiState) {
        val view = bubbleView ?: return
        val params = bubbleParams ?: return
        val dm = resources.displayMetrics
        val bubbleH = (64 * dm.density).toInt()
        val gap = (6 * dm.density).toInt()
        val barFull = (330 * dm.density).toInt()
        val barW = (64 * dm.density).toInt()
        params.x = bubblePos.first.coerceIn(8, (dm.widthPixels - barW - 8).coerceAtLeast(8))
        val barOpen = state is PopupUiState.Mini || (state is PopupUiState.Ready && state.multiSelect)
        if (!barOpen) {
            barOnTop.value = false
            params.gravity = Gravity.TOP or Gravity.START
            params.y = bubblePos.second
            runCatching { wm.updateViewLayout(view, params) }
            return
        }
        val belowSpace = dm.heightPixels - bubblePos.second - bubbleH - gap
        barOnTop.value = belowSpace < barFull
        if (barOnTop.value) {
            params.gravity = Gravity.BOTTOM or Gravity.START
            params.y = dm.heightPixels - (bubblePos.second + bubbleH)
        } else {
            params.gravity = Gravity.TOP or Gravity.START
            params.y = bubblePos.second
        }
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun onBubbleClick() {
        // toggle 语义：已有弹层（竖条/详情卡/失败卡）时点气泡=收回
        if (uiState.value !is PopupUiState.Hidden) {
            hidePopup()
            return
        }
        // 自动下载进行中：气泡拉出下载详情竖条
        if (autoTasks.isNotEmpty()) {
            uiState.value = PopupUiState.Downloads(autoTasks.values.toList())
            ensurePopupHost()
            dismissJob?.cancel()
            remainSeconds.value = 0
            return
        }
        // 单击 = 识别剪贴板：借道读取后直接走自动流水线，反馈全在气泡相位
        startManualRecognize()
    }

    /** 双击气泡：直接跳主界面 */
    private fun onBubbleDoubleClick() {
        startActivity(
            Intent(this, com.clipdown.app.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** 切换气泡相位；瞬态相位（revertMs 非空）到点自动回 Idle（仍有并发解析在跑则回 Parsing） */
    private fun setPhase(phase: BubblePhase, revertMs: Long? = null) {
        phaseRevertJob?.cancel()
        bubblePhase.value = phase
        if (revertMs != null) {
            phaseRevertJob = scope.launch {
                delay(revertMs)
                bubblePhase.value = if (parsingCount.intValue > 0) BubblePhase.Parsing else BubblePhase.Idle
            }
        }
    }

    /**
     * 点气泡识别：借道前台读剪贴板并直接走自动流水线（force：绕过识别记忆与抑制窗口）。
     * 识别期间不开弹窗——反馈全在气泡相位（黄圈解析→进度环/红闪）；
     * 剪贴板无链接或 gate 未能读取时弹迷你面板做文字提示。
     */
    private fun startManualRecognize() {
        manualRecognize = true
        ClipGateActivity.onResult = { found ->
            if (!found) {
                manualRecognize = false
                uiState.value = PopupUiState.Mini(hint = "未识别到链接")
            }
            // found 时由 LinkCenter.detected 接管，进入自动流水线
        }
        startActivity(Intent(this, ClipGateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // 兜底：gate 未能获取焦点等异常场景下给出提示
        recognizeTimeoutJob?.cancel()
        recognizeTimeoutJob = scope.launch {
            delay(4_000)
            if (manualRecognize && uiState.value is PopupUiState.Hidden) {
                manualRecognize = false
                uiState.value = PopupUiState.Mini(hint = "未能读取剪贴板")
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
                // 识别记忆：检测过的链接持久化，自动流程只给"新面孔"
                val seenBefore = settings.seenLinks.first()
                scope.launch { settings.markLinkSeen(link.url) }

                if (manualRecognize) {
                    manualRecognize = false
                    recognizeTimeoutJob?.cancel()
                    autoRecognize(link, force = true)
                    return@onEach
                }
                if (link.url in seenBefore) return@onEach
                if (autoPopupEnabled) autoRecognize(link) else pendingCount.intValue++
            }
            .launchIn(scope)
    }

    // ---------- 自动流水线（阶段 15）：解析 → 自动下载，无 UI 确认 ----------

    /**
     * 自动路径：后台解析（气泡黄圈）→ 单资源/视频变体组直接 enqueue（绿闪→进度环→紫闪），
     * 图集/多资源/自动下载不可用（关闭/熔断/仅WiFi）时回退到现有 Ready 选择卡，失败弹原因卡。
     * 弹窗被用户占用（Loading/Ready/Failed）时不抢，只更新气泡相位。
     */
    private fun autoRecognize(link: com.clipdown.app.clip.DetectedLink, force: Boolean = false) {
        if (link.url in pipelineUrls) return
        // force（点气泡识别）：绕过抑制窗口；pipelineUrls 保留——同链接正在跑时忽略重复点击
        if (!force && isSuppressed(link.url)) return
        pipelineUrls.add(link.url)
        parsingCount.intValue++
        pendingCount.intValue = 0
        setPhase(BubblePhase.Parsing)
        val job = scope.launch {
            // 博主主页：不做自动下载（集合形态没有"单个资源"可下），只提示打开主页
            if (tryProfilePath(link, force = false)) {
                autoParseJobs.remove(currentCoroutineContext()[Job])
                pipelineUrls.remove(link.url)
                parsingCount.intValue--
                return@launch
            }
            val result = withContext(Dispatchers.IO) { ParserEngine.parseSafe(link.url) }
            autoParseJobs.remove(currentCoroutineContext()[Job])
            pipelineUrls.remove(link.url)
            parsingCount.intValue--

            if (result.isSuccess) {
                val parsed = result.getOrThrow()
                LinkCenter.publishResult(parsed)
                // 污染取证：识别到"别的视频"时，从这里核对解析产物的每条媒体来源 URL
                android.util.Log.d(
                    "FloatingWindowService",
                    "解析成功 ${parsed.resolverId} media=${parsed.media.size} " +
                        parsed.media.joinToString("|") { "${it.kind.name[0]}:${it.url.take(70)}" }
                )
                val candidate = autoDownloadCandidate(parsed)
                if (candidate != null && canAutoDownload()) {
                    startAutoDownload(link, parsed, candidate)
                } else {
                    setPhase(BubblePhase.ParseOk, revertMs = 1_200)
                    if (popupFreeForAuto() && !parsed.isEmpty) {
                        uiState.value = PopupUiState.Ready(
                            link, parsed,
                            selectedIndices = setOf(0),
                            multiSelect = parsed.isAlbumMultiSelect
                        )
                        startDismissTimer()
                    }
                }
            } else {
                val e = result.exceptionOrNull()
                setPhase(BubblePhase.ParseFail, revertMs = 4_000)
                if (popupFreeForAuto()) {
                    val msg = (e as? ParseException)?.message ?: e?.message ?: "解析失败"
                    uiState.value = PopupUiState.Failed(
                        link, msg,
                        retryable = (e as? ParseException)?.retryable ?: true
                    )
                    ensurePopupHost()
                    startDismissTimer()
                }
            }
        }
        autoParseJobs.add(job)
    }

    /**
     * 自动下载候选：单资源直取；多条但全为视频（同源清晰度变体，如 reel 原画质+备选、
     * X 多码率）取推荐首位（与选择卡默认选中一致）；图集/多图/混合形态返回 null。
     */
    private fun autoDownloadCandidate(result: ParseResult): MediaItem? =
        if (result.isAlbumMultiSelect) null else result.media.firstOrNull()

    /** 自动下载前置检查：开关、失败退避熔断、仅 WiFi 保险丝 */
    private fun canAutoDownload(): Boolean {
        if (!autoDownloadEnabled) return false
        if (System.currentTimeMillis() < autoDownloadSuspendedUntil) return false
        if (wifiOnlyEnabled && !isOnUnmetered()) return false
        return true
    }

    private fun isOnUnmetered(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun startAutoDownload(link: com.clipdown.app.clip.DetectedLink, parsed: ParseResult, item: MediaItem) {
        scope.launch {
            runCatching {
                val taskId = withContext(Dispatchers.IO) {
                    DownloadController.enqueue(item, parsed.platform, parsed.title, parsed.sourceUrl)
                }
                autoTasks[taskId] = DownloadRow(
                    taskId = taskId,
                    title = parsed.title ?: "未命名作品",
                    platformName = parsed.platform.displayName,
                    percent = 0
                )
                downloadingCount.intValue++
                startService(DownloadService.intent(this@FloatingWindowService, DownloadService.ACTION_RESUME))
                // 绿圈短闪后转入下载进度环（进度事件先到则由 watchAutoTask 直接切相位）
                setPhase(BubblePhase.ParseOk)
                scope.launch {
                    delay(600)
                    if (bubblePhase.value == BubblePhase.ParseOk) setPhase(BubblePhase.Downloading(0))
                }
                watchAutoTask(taskId, link.url)
            }.onFailure {
                setPhase(BubblePhase.ParseFail, revertMs = 4_000)
            }
        }
    }

    /** 订阅自动任务进度：更新气泡进度环 + 详情卡；终态回写抑制表与退避计数 */
    private fun watchAutoTask(taskId: String, url: String) {
        scope.launch(Dispatchers.Main.immediate) {
            DownloadController.engine().progress.collect { e ->
                if (e.taskId != taskId) return@collect
                val row = autoTasks[taskId] ?: return@collect
                when (e.status) {
                    DownloadStatus.COMPLETED,
                    DownloadStatus.FAILED,
                    DownloadStatus.CANCELED -> {
                        autoTasks.remove(taskId)
                        downloadingCount.intValue--
                        when (e.status) {
                            DownloadStatus.COMPLETED -> {
                                consecutiveFailures = 0
                                autoDownloadSuspendedUntil = 0L
                                markSuppressed(url)
                                setPhase(BubblePhase.DownloadOk, revertMs = 2_000)
                            }
                            DownloadStatus.FAILED -> {
                                consecutiveFailures++
                                if (consecutiveFailures >= AUTO_BACKOFF_THRESHOLD) {
                                    autoDownloadSuspendedUntil =
                                        System.currentTimeMillis() + AUTO_BACKOFF_MS
                                    consecutiveFailures = 0
                                }
                                setPhase(BubblePhase.DownloadFail, revertMs = 6_000)
                            }
                            else -> if (autoTasks.isEmpty()) setPhase(BubblePhase.Idle)
                        }
                        refreshDownloadsCard()
                        currentCoroutineContext()[Job]?.cancel()
                    }
                    DownloadStatus.MERGING -> {
                        autoTasks[taskId] = row.copy(percent = 99)
                        setPhase(BubblePhase.Downloading(null))
                        refreshDownloadsCard()
                    }
                    else -> {
                        if (e.totalBytes > 0) {
                            val pct = ((e.downloadedBytes * 100) / e.totalBytes).toInt()
                            autoTasks[taskId] = row.copy(
                                percent = pct,
                                sizeText = "${MediaItem.formatSize(e.downloadedBytes)} / ${MediaItem.formatSize(e.totalBytes)}"
                            )
                            setPhase(BubblePhase.Downloading(pct))
                            refreshDownloadsCard()
                        }
                    }
                }
            }
        }
    }

    /** 详情卡开着时随进度实时刷新；任务清空即收起 */
    private fun refreshDownloadsCard() {
        val cur = uiState.value
        if (cur !is PopupUiState.Downloads) return
        if (autoTasks.isEmpty()) hidePopup()
        else uiState.value = PopupUiState.Downloads(autoTasks.values.toList())
    }

    private fun markSuppressed(url: String) {
        suppressedUrls[url] = System.currentTimeMillis()
    }

    private fun isSuppressed(url: String): Boolean {
        val now = System.currentTimeMillis()
        suppressedUrls.entries.removeAll { now - it.value > SUPPRESS_MS }
        return url in suppressedUrls
    }

    /** 自动路径只在弹窗空闲（隐藏/迷你面板）时占用弹窗；用户正在看的弹窗不抢 */
    private fun popupFreeForAuto(): Boolean = when (uiState.value) {
        is PopupUiState.Hidden, is PopupUiState.Mini -> true
        else -> false
    }

    /**
     * 博主主页专用分支：识别 → 解析 → 进入"确认 + 打开主页"状态。
     *
     * 主页是"1 作者 + N 笔记"的集合形态，气泡里无法逐篇挑选，
     * 所以这里只负责把结果准备好（存进 [ProfileCenter]）并提示用户进页面。
     *
     * @param force true=手动路径（用户点了气泡，弹窗状态一定由我们接管）；
     *              false=自动路径（只在弹窗空闲时占用，避免抢用户正在看的弹窗）
     * @return 是否命中主页链路（false 表示这是普通作品链接，调用方继续走原逻辑）
     */
    private suspend fun tryProfilePath(
        link: com.clipdown.app.clip.DetectedLink,
        force: Boolean
    ): Boolean {
        val kind = withContext(Dispatchers.IO) {
            runCatching { ParserEngine.linkKind(link.url) }.getOrDefault(LinkKind.UNKNOWN)
        }
        if (kind != LinkKind.PROFILE) return false

        val r = withContext(Dispatchers.IO) { runCatching { ParserEngine.parseProfile(link.url) } }
        val profile = r.getOrNull()
        val e = r.exceptionOrNull()
        android.util.Log.d(
            "FloatingWindowService",
            "主页链路：url=${link.url.take(80)} ok=${profile != null} posts=${profile?.posts?.size} err=${e?.message}"
        )

        if (profile != null) {
            ProfileCenter.adopt(link.url, profile)
            setPhase(BubblePhase.ParseOk, revertMs = 1_200)
            if (force || popupFreeForAuto()) {
                uiState.value = PopupUiState.ProfileReady(
                    link = link,
                    nickname = profile.displayName,
                    postCount = profile.posts.size,
                    platformName = profile.platform.displayName,
                    warning = profile.warning
                )
                ensurePopupHost()
                startDismissTimer()
            }
        } else {
            setPhase(BubblePhase.ParseFail, revertMs = 4_000)
            if (force || popupFreeForAuto()) {
                val msg = (e as? ParseException)?.message ?: e?.message ?: "主页解析失败"
                uiState.value = PopupUiState.Failed(
                    link, msg,
                    retryable = (e as? ParseException)?.retryable ?: true
                )
                ensurePopupHost()
                startDismissTimer()
            }
        }
        return true
    }

    /** 手动路径（迷你面板识别/重试/SHOW_LAST）：保留完整弹窗交互 */
    private fun showAndParse(link: com.clipdown.app.clip.DetectedLink) {
        pendingCount.intValue = 0
        setPhase(BubblePhase.Parsing)
        uiState.value = PopupUiState.Loading(link)
        ensurePopupHost()
        startDismissTimer()

        parseJob?.cancel()
        parseJob = scope.launch {
            // 博主主页：集合形态，不走单篇解析链路，只做"确认 + 打开主页"
            if (tryProfilePath(link, force = true)) return@launch

            val result = withContext(Dispatchers.IO) { ParserEngine.parseSafe(link.url) }
            val current = uiState.value
            if (current !is PopupUiState.Loading) {
                // 弹窗已被超时/新流程接管：Parsing 无自动回退，需在此收口
                if (bubblePhase.value == BubblePhase.Parsing) setPhase(BubblePhase.Idle)
                return@launch
            }

            if (result.isSuccess) {
                val parsed = result.getOrThrow()
                LinkCenter.publishResult(parsed)
                setPhase(BubblePhase.ParseOk, revertMs = 1_200)
                uiState.value = PopupUiState.Ready(
                    link, parsed,
                    selectedIndices = setOf(0),
                    multiSelect = parsed.isAlbumMultiSelect
                )
            } else {
                val e = result.exceptionOrNull()
                val msg = (e as? ParseException)?.message ?: e?.message ?: "解析失败"
                setPhase(BubblePhase.ParseFail, revertMs = 4_000)
                uiState.value = PopupUiState.Failed(link, msg, retryable = (e as? ParseException)?.retryable ?: true)
            }
            startDismissTimer()
        }
    }

    /** 打开博主主页页：解析结果已在 [ProfileCenter] 里，页面直接展示，不再重复请求 */
    private fun openProfilePage() {
        runCatching {
            startActivity(
                com.clipdown.app.ui.MainActivity
                    .intent(this, openProfile = true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        hidePopup()
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
                                uiState.value = s.copy(
                                    selectedIndices = if (s.multiSelect) {
                                        if (index in s.selectedIndices) s.selectedIndices - index
                                        else s.selectedIndices + index
                                    } else {
                                        setOf(index)
                                    }
                                )
                                startDismissTimer()
                            }
                        },
                        onDownload = { downloadCurrent() },
                        onRetry = { LinkCenter.last.value?.let { showAndParse(it) } },
                        onOpenApp = { openApp() },
                        onOpenProfile = { openProfilePage() },
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
        // 弹窗超时收起时解析仍在后台跑的话，Parsing 无自动回退，在这里收口；
        // Downloading 相位不动（下载在后台继续，气泡进度环要保留）
        if (bubblePhase.value == BubblePhase.Parsing) setPhase(BubblePhase.Idle)
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
        val items = state.selectedIndices.mapNotNull { state.result.media.getOrNull(it) }
        if (items.isEmpty()) return

        // 图集多选：批量入队后收起弹窗，进度由气泡徽标与下载详情卡承接
        if (state.multiSelect) {
            scope.launch {
                runCatching {
                    val taskIds = withContext(Dispatchers.IO) {
                        DownloadController.enqueueAll(items, state.result.platform, state.result.title, state.result.sourceUrl)
                    }
                    downloadingCount.intValue += taskIds.size
                    markSuppressed(state.link.url)
                    startService(DownloadService.intent(this@FloatingWindowService, DownloadService.ACTION_RESUME))
                }
                hidePopup()
            }
            return
        }

        val item = items.first()
        // 下载中不自动收起弹窗：全部流程在弹窗内闭环；用户也可点空白收起，后台继续
        dismissJob?.cancel()
        remainSeconds.value = 0
        setPhase(BubblePhase.Downloading(0))
        uiState.value = state.copy(downloading = true, downloadPercent = 0)

        downloadJob?.cancel()
        downloadJob = scope.launch(Dispatchers.Main.immediate) {
            val taskId = withContext(Dispatchers.IO) {
                DownloadController.enqueue(item, state.result.platform, state.result.title, state.result.sourceUrl)
            }
            downloadingCount.intValue++
            startService(DownloadService.intent(this@FloatingWindowService, DownloadService.ACTION_RESUME))

            // 订阅进度：下载/合并/完成/失败全部在弹窗内呈现
            DownloadController.engine().progress.collect { e ->
                if (e.taskId != taskId) return@collect
                when (e.status) {
                    DownloadStatus.COMPLETED,
                    DownloadStatus.FAILED,
                    DownloadStatus.CANCELED -> {
                        // 终态簿记与弹窗可见性无关：弹窗已收起时下载也在后台完成
                        when (e.status) {
                            DownloadStatus.COMPLETED -> {
                                markSuppressed(state.link.url)
                                setPhase(BubblePhase.DownloadOk, revertMs = 2_000)
                            }
                            DownloadStatus.FAILED ->
                                setPhase(BubblePhase.DownloadFail, revertMs = 6_000)
                            else -> setPhase(BubblePhase.Idle)
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
                        pendingCount.intValue = 0
                        downloadingCount.intValue--
                        currentCoroutineContext()[Job]?.cancel()
                    }
                    com.clipdown.downloader.model.DownloadStatus.MERGING -> {
                        setPhase(BubblePhase.Downloading(null))
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
                            setPhase(BubblePhase.Downloading(pct))
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

        /** 双击判定窗口（ms）；单击行为延迟到窗口过期后执行 */
        private const val DOUBLE_TAP_MS = 250L
        private const val EXTRA_PHASE = "phase"
        private const val EXTRA_PERCENT = "percent"

        /** 自动下载失败退避（IG 风控保险丝）：连续失败 N 次暂停一段时间 */
        private const val AUTO_BACKOFF_THRESHOLD = 3
        private const val AUTO_BACKOFF_MS = 10 * 60_000L

        const val ACTION_HIDE_POPUP = "com.clipdown.app.action.HIDE_POPUP"
        const val ACTION_SHOW_LAST = "com.clipdown.app.action.SHOW_LAST"
        const val ACTION_DEBUG_PHASE = "com.clipdown.app.action.DEBUG_PHASE"

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

// ---------- 气泡视觉 ----------

private val HaloBlue = Color(0xFF4D9FFF)

/** 相位强调色：描边与光环共用，0.3s 过渡 */
private fun BubblePhase.accent(): Color = when (this) {
    BubblePhase.Idle -> Color.White.copy(alpha = 0.35f)
    BubblePhase.Parsing -> Color(0xFFFFC53D)
    BubblePhase.ParseOk -> Color(0xFF2EB872)
    BubblePhase.ParseFail -> Color(0xFFE5484D)
    is BubblePhase.Downloading -> HaloBlue
    BubblePhase.DownloadOk -> Color(0xFFA78BFA)
    BubblePhase.DownloadFail -> Color(0xFFE5484D)
}

/**
 * 气泡窗口整体：圆形气泡（含徽标/动效）+ 可延伸竖条（Mini/图集），同一窗口纵向连接——
 * 竖条开合只改窗口高度，永不遮挡气泡（修复 12）。barOnTop=true 时竖条渲染在气泡上方。
 * 触摸分区在 addBubble 的 View 监听器：气泡圆区域=拖拽/toggle，竖条区=Compose 控件。
 */
@Composable
private fun BubbleWindowContent(
    phase: BubblePhase,
    pendingCount: Int,
    parsingCount: Int,
    downloadingCount: Int,
    barState: PopupUiState?,
    barOnTop: Boolean,
    onToggle: () -> Unit,
    onRecognize: () -> Unit,
    onSelect: (Int) -> Unit,
    onDownload: () -> Unit,
    onBarStateChanged: (PopupUiState?) -> Unit
) {
    // 竖条开合/内容变化 → 同步窗口锚点与延伸方向（Compose 状态驱动，替代 Flow）
    LaunchedEffect(barState) { onBarStateChanged(barState) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (barOnTop && barState != null) {
            BarBody(state = barState, onRecognize = onRecognize, onSelect = onSelect, onDownload = onDownload)
        }
        BubbleCircle(phase, pendingCount, parsingCount, downloadingCount)
        if (!barOnTop && barState != null) {
            BarBody(state = barState, onRecognize = onRecognize, onSelect = onSelect, onDownload = onDownload)
        }
    }
}

/**
 * 圆形气泡：头像 logo + 相位光环（Canvas 绘制，不重布局）+ 三个计数徽标。
 * 左上绿 = 搁置待处理；左下黄 = 解析中 N；右下蓝 = 下载中 M（阶段 16 并发可见性）。
 *
 * 注意：这里刻意不放任何 pointerInput——Compose 内容一旦认领触摸事件，
 * 服务层 View 级拖拽/单击监听器就收不到事件（见 addBubble）。
 */
@Composable
private fun BubbleCircle(
    phase: BubblePhase,
    pendingCount: Int,
    parsingCount: Int,
    downloadingCount: Int
) {
    val infinite = rememberInfiniteTransition(label = "bubbleHalo")
    val breathe by infinite.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "breathe"
    )
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "spin"
    )
    val accent by animateColorAsState(phase.accent(), tween(300), label = "accent")

    Box(
        modifier = Modifier.size(64.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(64.dp)) {
            val stroke = 2.5.dp.toPx()
            val radius = size.minDimension / 2f - stroke
            when (phase) {
                // 空闲：蓝色呼吸光圈（alpha 呼吸只触发 draw 重绘，无重组/布局开销）
                BubblePhase.Idle -> drawCircle(
                    HaloBlue.copy(alpha = breathe),
                    radius = radius,
                    style = Stroke(stroke)
                )
                // 解析中：黄色旋转光弧
                BubblePhase.Parsing -> rotate(spin) {
                    drawArc(
                        accent,
                        startAngle = 0f,
                        sweepAngle = 110f,
                        useCenter = false,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
                // 下载中：进度环（percent=null 时为不确定旋转弧，即合并阶段）
                is BubblePhase.Downloading -> {
                    val pct = phase.percent
                    if (pct == null) {
                        rotate(spin) {
                            drawArc(
                                accent,
                                startAngle = 0f,
                                sweepAngle = 120f,
                                useCenter = false,
                                style = Stroke(stroke, cap = StrokeCap.Round)
                            )
                        }
                    } else {
                        drawCircle(accent.copy(alpha = 0.18f), radius = radius, style = Stroke(stroke))
                        drawArc(
                            accent,
                            startAngle = -90f,
                            sweepAngle = 360f * pct / 100f,
                            useCenter = false,
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                // 闪现类相位：静态光环，颜色即信号（描边同步变色，到点自动回 Idle）
                BubblePhase.ParseOk,
                BubblePhase.DownloadOk,
                BubblePhase.ParseFail,
                BubblePhase.DownloadFail -> drawCircle(accent, radius = radius, style = Stroke(stroke))
            }
        }
        // 头像 logo：描边颜色随相位 0.3s 过渡
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color(0xFF141824))
                .border(1.5.dp, accent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(com.clipdown.app.R.drawable.bubble_logo),
                contentDescription = "剪存",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // 徽标：左上绿=搁置待处理，左下黄=解析中，右下蓝=下载中（>0 才显示，9+ 封顶）
        if (pendingCount > 0) CountBadge(pendingCount, Color(0xFF2EB872), Modifier.align(Alignment.TopStart))
        if (parsingCount > 0) CountBadge(parsingCount, Color(0xFFFFC53D), Modifier.align(Alignment.BottomStart))
        if (downloadingCount > 0) CountBadge(downloadingCount, HaloBlue, Modifier.align(Alignment.BottomEnd))
    }
}

/** 计数徽标：数字圆点，深色描边与 logo 融合（调用方处于 BoxScope，传入对齐修饰符） */
@Composable
private fun CountBadge(count: Int, color: Color, align: Modifier) {
    Box(
        modifier = align
            .size(16.dp)
            .background(color, CircleShape)
            .border(1.5.dp, Color(0xFF141824), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (count > 9) "9+" else count.toString(),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

package com.clipdown.app.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.clipdown.app.data.CookieStore
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.parser.model.Platform
import com.clipdown.parser.parsers.IgRiskGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 内置浏览器快速一键登录弹窗。
 *
 * 核心技术点：
 * 1. 采用直角规范 (RectangleShape) 与全屏安全视口；
 * 2. 剥离 X-Requested-With 请求头，解除 Meta/Instagram 针对 Android 嵌入式 WebView 的反爬白屏封锁；
 * 3. 规避移动端空壳路径，针对 Instagram 采用带 next 参数的标准登录入口；
 * 4. 深度过滤外部 DeepLink (instagram:// 等)，防止因 scheme 未知导致 WebView 终止渲染抛出 net::ERR_UNKNOWN_URL_SCHEME 白屏；
 * 5. SSL 容错与主帧错误友好重试面板；
 * 6. 严密会话校验：未检测到有效 sessionid/token 时拒绝以匿名 Cookie 充数；
 * 7. 支持一键切换「电脑版 / 手机版」与「系统浏览器打开」多重保障通道。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginDialog(
    platform: Platform,
    onDismiss: () -> Unit,
    onCookieSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val defaultUa = remember {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
            ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }

    // 剥离 Android 嵌入式 WebView 标记（; wv 与 Version/4.0），
    // 呈现手机原生独立运行的 Google Chrome 浏览器，与底层真实硬件（屏幕分辨率、触摸点、GPU）100% 吻合，
    // 绝不触发 Meta 针对 Windows 桌面 UA 伪装的欺诈拦截
    val cleanMobileUa = remember(defaultUa) {
        defaultUa.replace("; wv", "").replace(Regex("""Version/\d+\.\d+\s*"""), "")
    }

    val cleanDesktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    var isDesktopMode by remember { mutableStateOf(false) }

    val loginUrl = when (platform) {
        Platform.INSTAGRAM -> "https://www.instagram.com/accounts/login/"
        Platform.XIAOHONGSHU -> "https://www.xiaohongshu.com/login"
        Platform.FACEBOOK -> "https://m.facebook.com/login/"
        else -> "https://www.instagram.com/"
    }

    val watchDomain = when (platform) {
        Platform.INSTAGRAM -> "https://www.instagram.com/"
        Platform.XIAOHONGSHU -> "https://www.xiaohongshu.com/"
        Platform.FACEBOOK -> "https://m.facebook.com/"
        else -> loginUrl
    }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(loginUrl) }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var hasMainFrameError by remember { mutableStateOf(false) }
    var mainFrameErrorMessage by remember { mutableStateOf<String?>(null) }
    var loginCaptured by remember { mutableStateOf(false) }
    var showManualPasteDialog by remember { mutableStateOf(false) }

    fun resetWebViewCache() {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        runCatching { WebStorage.getInstance().deleteAllData() }
        webViewInstance?.apply {
            clearCache(true)
            clearHistory()
            clearFormData()
            loadUrl(loginUrl)
        }
        Toast.makeText(context, "已彻底清理登录缓存与历史会话，正在重新加载…", Toast.LENGTH_SHORT).show()
    }

    fun getAggregatedCookies(fromUrl: String?): String {
        val cm = CookieManager.getInstance()
        runCatching { cm.flush() }
        val targetUrls = when (platform) {
            Platform.INSTAGRAM -> listOf(
                "https://www.instagram.com/",
                "https://instagram.com/",
                "https://i.instagram.com/",
                "https://accountscenter.instagram.com/",
                "https://www.instagram.com/accounts/login/",
                "https://www.instagram.com/challenge/"
            )
            Platform.XIAOHONGSHU -> listOf(
                "https://www.xiaohongshu.com/",
                "https://xiaohongshu.com/",
                "https://edith.xiaohongshu.com/"
            )
            Platform.FACEBOOK -> listOf(
                "https://m.facebook.com/",
                "https://www.facebook.com/",
                "https://facebook.com/"
            )
            else -> listOf(watchDomain)
        }.toMutableList()

        if (!fromUrl.isNullOrBlank()) {
            targetUrls.add(0, fromUrl)
        }

        val cookieMap = LinkedHashMap<String, String>()
        for (url in targetUrls.distinct()) {
            val raw = runCatching { cm.getCookie(url) }.getOrNull() ?: continue
            for (pair in raw.split(';')) {
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) continue
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx > 0) {
                    val k = trimmed.substring(0, eqIdx).trim()
                    val v = trimmed.substring(eqIdx + 1).trim()
                    if (k.isNotEmpty() && !cookieMap.containsKey(k)) {
                        cookieMap[k] = v
                    }
                }
            }
        }
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun checkAndSaveCookies(fromUrl: String): Boolean {
        if (loginCaptured) return true
        val cookie = getAggregatedCookies(fromUrl)
        if (cookie.isBlank()) return false

        if (CookieStore.isValidSessionCookie(platform, cookie)) {
            loginCaptured = true
            CookieStore.put(context, platform, cookie)
            IgRiskGuard.reset()
            Toast.makeText(context, "${platform.displayName} 登录成功，已自动捕获有效登录凭据！", Toast.LENGTH_SHORT).show()
            scope.launch {
                delay(800)
                onCookieSaved(cookie)
                onDismiss()
            }
            return true
        }
        return false
    }

    // 后台轻量轮询嗅探（提高为 600ms 高频嗅探，两步验证通过瞬间即刻捕获）
    LaunchedEffect(platform) {
        while (!loginCaptured) {
            delay(600)
            checkAndSaveCookies(currentUrl)
        }
    }

    BackHandler {
        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onDismiss()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            shape = RectangleShape
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // 顶部操作栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (webViewInstance?.canGoBack() == true) {
                                webViewInstance?.goBack()
                            } else {
                                onDismiss()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = AppTheme.spacing.xs)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "登录 ${platform.displayName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            if (isDesktopMode) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "[电脑版]",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            text = if (loginCaptured) "✓ 已捕获登录态，正在完成…" else currentUrl.substringBefore('?').take(40),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (loginCaptured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 切换电脑版 / 手机版 UA（解决个别平台移动端限制）
                    IconButton(
                        onClick = {
                            isDesktopMode = !isDesktopMode
                            webViewInstance?.settings?.userAgentString = if (isDesktopMode) cleanDesktopUa else cleanMobileUa
                            hasMainFrameError = false
                            webViewInstance?.reload()
                        }
                    ) {
                        Icon(
                            imageVector = if (isDesktopMode) Icons.Default.PhoneAndroid else Icons.Default.DesktopWindows,
                            contentDescription = if (isDesktopMode) "切换为手机版" else "切换为电脑版",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 外部浏览器打开
                    IconButton(
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(context, "无法唤起外部浏览器", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "系统浏览器打开",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 刷新
                    IconButton(
                        onClick = {
                            hasMainFrameError = false
                            webViewInstance?.reload()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // 手动完成/保存按钮（严格校验 sessionid，杜绝游客空 Cookie 充数）
                    Button(
                        onClick = {
                            val cookie = getAggregatedCookies(currentUrl)
                            if (CookieStore.isValidSessionCookie(platform, cookie)) {
                                CookieStore.put(context, platform, cookie)
                                IgRiskGuard.reset()
                                Toast.makeText(context, "${platform.displayName} 登录凭据提取成功！", Toast.LENGTH_SHORT).show()
                                onCookieSaved(cookie)
                                onDismiss()
                            } else {
                                val tokenName = when (platform) {
                                    Platform.INSTAGRAM -> "sessionid"
                                    Platform.XIAOHONGSHU -> "web_session"
                                    Platform.FACEBOOK -> "c_user"
                                    else -> "核心鉴权令牌"
                                }
                                Toast.makeText(
                                    context,
                                    "未检测到有效登录态（缺少 $tokenName），请先在页面中登录账号后再点完成！亦可使用「粘贴Cookie」备用通道。",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .height(36.dp)
                            .padding(horizontal = 4.dp)
                    ) {
                        Text("完成", style = MaterialTheme.typography.labelMedium)
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 进度条
                if (isLoading && loadProgress < 1f) {
                    LinearProgressIndicator(
                        progress = { loadProgress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }

                // 状态提示横幅
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                loginCaptured -> MaterialTheme.colorScheme.primaryContainer
                                hasMainFrameError -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        .padding(horizontal = AppTheme.spacing.md, vertical = AppTheme.spacing.xs)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (loginCaptured) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(AppTheme.spacing.xs))
                            Text(
                                "✓ 已成功捕获有效登录凭据，正在保存…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        } else if (hasMainFrameError) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(AppTheme.spacing.xs))
                            Text(
                                "页面加载受阻：请确保已打开加速器/VPN；亦可点击「重置缓存」或切换「电脑版」重试",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(AppTheme.spacing.xs))
                            OutlinedButton(
                                onClick = { resetWebViewCache() },
                                shape = RectangleShape,
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("重置缓存", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            Text(
                                "提示：若遇验证死循环，点「重置缓存」清空被标记的会话；亦可点「粘贴Cookie」",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(AppTheme.spacing.xs))
                            OutlinedButton(
                                onClick = { resetWebViewCache() },
                                shape = RectangleShape,
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                                Text("重置缓存", style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(4.dp))
                            OutlinedButton(
                                onClick = { showManualPasteDialog = true },
                                shape = RectangleShape,
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(2.dp))
                                Text("粘贴Cookie", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // WebView 主体 + 错误友好覆盖层
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewInstance = this
                                val cm = CookieManager.getInstance()
                                cm.setAcceptCookie(true)
                                cm.setAcceptThirdPartyCookies(this, true)

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    javaScriptCanOpenWindowsAutomatically = true
                                    setSupportMultipleWindows(true)
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    allowFileAccess = false
                                    allowContentAccess = true
                                    userAgentString = if (isDesktopMode) cleanDesktopUa else cleanMobileUa
                                }

                                // 官方标准解除 X-Requested-With 请求头注入（剥离包名标记，解除 Meta 反爬白屏拦截）
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                                    runCatching {
                                        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                                    }
                                }

                                val stealthJs = """
                                    try {
                                        Object.defineProperty(navigator, 'webdriver', {get: () => undefined});
                                        if (!window.chrome) { window.chrome = { runtime: {} }; }
                                    } catch(e) {}
                                """.trimIndent()

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress / 100f
                                        isLoading = newProgress < 100
                                    }

                                    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                                        Toast.makeText(context, message ?: "", Toast.LENGTH_SHORT).show()
                                        result?.confirm()
                                        return true
                                    }

                                    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                                        result?.confirm()
                                        return true
                                    }

                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message?
                                    ): Boolean {
                                        // 拦截 window.open 并在主 WebView 内原窗加载（解决 2FA/Facebook 弹窗流程中断）
                                        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                        val tempWebView = WebView(view!!.context)
                                        tempWebView.webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                                req?.url?.let { view.loadUrl(it.toString()) }
                                                return true
                                            }
                                        }
                                        transport.webView = tempWebView
                                        resultMsg.sendToTarget()
                                        return true
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val uri = request?.url ?: return false
                                        val scheme = uri.scheme?.lowercase() ?: return false
                                        if (scheme != "http" && scheme != "https") {
                                            if (scheme == "intent") {
                                                runCatching {
                                                    val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                                                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                                    if (!fallbackUrl.isNullOrBlank()) {
                                                        view?.loadUrl(fallbackUrl)
                                                        return true
                                                    }
                                                }
                                            }
                                            // 拦截第三方客户端协议跳转（如 instagram:// 等），防止 WebView 内部抛出 ERR_UNKNOWN_URL_SCHEME 导致白屏
                                            return true
                                        }
                                        return false
                                    }

                                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                        // 国内加速器代理或自签证书容错，避免直接阻断白屏
                                        handler?.proceed()
                                    }

                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                        if (request?.isForMainFrame == true) {
                                            hasMainFrameError = true
                                            mainFrameErrorMessage = error?.description?.toString()
                                        }
                                    }

                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        url?.let {
                                            currentUrl = it
                                            hasMainFrameError = false
                                            view?.evaluateJavascript(stealthJs, null)
                                            checkAndSaveCookies(it)
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        url?.let {
                                            currentUrl = it
                                            view?.evaluateJavascript(stealthJs, null)
                                            checkAndSaveCookies(it)
                                        }
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                        url?.let {
                                            currentUrl = it
                                            view?.evaluateJavascript(stealthJs, null)
                                            checkAndSaveCookies(it)
                                        }
                                    }
                                }

                                loadUrl(loginUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 主帧加载失败兜底面板
                    if (hasMainFrameError) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
                            shape = RectangleShape
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(AppTheme.spacing.xl),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(AppTheme.spacing.md))
                                Text(
                                    text = "${platform.displayName} 页面加载受阻",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(AppTheme.spacing.xs))
                                Text(
                                    text = mainFrameErrorMessage ?: "网络连接超时或无法建立安全连接。请确认海外网络加速器已开启且代理了此应用。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(AppTheme.spacing.lg))
                                Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.md)) {
                                    OutlinedButton(
                                        onClick = {
                                            isDesktopMode = !isDesktopMode
                                            webViewInstance?.settings?.userAgentString = if (isDesktopMode) cleanDesktopUa else cleanMobileUa
                                            hasMainFrameError = false
                                            webViewInstance?.reload()
                                        },
                                        shape = RectangleShape
                                    ) {
                                        Text(if (isDesktopMode) "切回手机版" else "切换电脑版重试")
                                    }
                                    Button(
                                        onClick = {
                                            hasMainFrameError = false
                                            webViewInstance?.reload()
                                        },
                                        shape = RectangleShape
                                    ) {
                                        Text("重新加载")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManualPasteDialog) {
        var pasteInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualPasteDialog = false },
            shape = RectangleShape,
            title = {
                Text(
                    "手动导入 ${platform.displayName} Cookie",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        "若内置浏览器遭遇风控验证死循环，可从其他浏览器导入已登录的 Cookie：\n" +
                        "• 手机免电脑方案（推荐）：使用极简「Via 浏览器」（2MB），登录 Instagram 后点「工具 → 网页资源 → Cookie」即可一键复制并粘贴在此；\n" +
                        "• 电脑端方案：浏览器登录后按 F12 → Application/存储 → Cookies，复制 sessionid。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(AppTheme.spacing.sm))
                    OutlinedTextField(
                        value = pasteInput,
                        onValueChange = { pasteInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("粘贴包含核心令牌的完整 Cookie 文本…") },
                        maxLines = 4,
                        shape = RectangleShape
                    )
                    Spacer(Modifier.height(AppTheme.spacing.xs))
                    OutlinedButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text ?: ""
                            if (clipText.isNotBlank()) {
                                pasteInput = clipText.trim()
                            } else {
                                Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RectangleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("读取剪贴板内容", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = pasteInput.trim()
                        if (CookieStore.isValidSessionCookie(platform, trimmed)) {
                            CookieStore.put(context, platform, trimmed)
                            IgRiskGuard.reset()
                            Toast.makeText(context, "${platform.displayName} Cookie 保存成功！", Toast.LENGTH_SHORT).show()
                            showManualPasteDialog = false
                            onCookieSaved(trimmed)
                            onDismiss()
                        } else {
                            val tokenName = when (platform) {
                                Platform.INSTAGRAM -> "sessionid"
                                Platform.XIAOHONGSHU -> "web_session"
                                Platform.FACEBOOK -> "c_user"
                                else -> "核心鉴权令牌"
                            }
                            Toast.makeText(
                                context,
                                "Cookie 无效：未包含核心鉴权令牌 ($tokenName)！请重新复制。",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    shape = RectangleShape
                ) {
                    Text("确认保存")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showManualPasteDialog = false },
                    shape = RectangleShape
                ) {
                    Text("取消")
                }
            }
        )
    }
}

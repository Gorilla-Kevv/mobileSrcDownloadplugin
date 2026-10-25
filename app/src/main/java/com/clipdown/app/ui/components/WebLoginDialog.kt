package com.clipdown.app.ui.components

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
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
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

    val mobileUa = "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    val desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    var isDesktopMode by remember { mutableStateOf(false) }

    val loginUrl = when (platform) {
        Platform.INSTAGRAM -> "https://www.instagram.com/accounts/login/?next=%2F"
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

    fun checkAndSaveCookies(fromUrl: String): Boolean {
        if (loginCaptured) return true
        val cm = CookieManager.getInstance()
        val cookie = cm.getCookie(fromUrl) ?: cm.getCookie(watchDomain) ?: ""
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

    // 后台轻量轮询嗅探
    LaunchedEffect(platform) {
        while (!loginCaptured) {
            delay(1000)
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
                            webViewInstance?.settings?.userAgentString = if (isDesktopMode) desktopUa else mobileUa
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
                            val cm = CookieManager.getInstance()
                            val cookie = cm.getCookie(currentUrl) ?: cm.getCookie(watchDomain) ?: ""
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
                                    "未检测到有效登录态（缺少 $tokenName），请先在页面中登录账号后再点完成！",
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                "页面加载受阻：请确保已打开加速器/VPN；亦可点击右上角切换为「电脑版」重试",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        } else {
                            Text(
                                "提示：在下方官方页面安全登录后会自动捕获凭据；若页面白屏请确保 VPN 开启或切换右上角电脑版。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                                    setSupportMultipleWindows(false)
                                    cacheMode = WebSettings.LOAD_DEFAULT
                                    allowFileAccess = false
                                    allowContentAccess = true
                                    userAgentString = if (isDesktopMode) desktopUa else mobileUa
                                }

                                // 官方标准解除 X-Requested-With 请求头注入（剥离包名标记，解除 Meta 反爬白屏拦截）
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                                    runCatching {
                                        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress / 100f
                                        isLoading = newProgress < 100
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                        val uri = request?.url ?: return false
                                        val scheme = uri.scheme?.lowercase() ?: return false
                                        if (scheme != "http" && scheme != "https") {
                                            // 拦截第三方客户端协议跳转（如 instagram://、intent:// 等），防止 WebView 内部抛出 ERR_UNKNOWN_URL_SCHEME 导致白屏
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
                                            checkAndSaveCookies(it)
                                        }
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        url?.let {
                                            currentUrl = it
                                            checkAndSaveCookies(it)
                                        }
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                        url?.let {
                                            currentUrl = it
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
                                            webViewInstance?.settings?.userAgentString = if (isDesktopMode) desktopUa else mobileUa
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
}

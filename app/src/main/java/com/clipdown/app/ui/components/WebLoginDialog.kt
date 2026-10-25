package com.clipdown.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.clipdown.app.data.CookieStore
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.parser.model.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 内置浏览器快速一键登录弹窗。
 *
 * 解决移动端用户无法自行提取 Cookie 的痛点：
 * 1. 弹出内置 WebView 加载对应平台的官方移动端登录页；
 * 2. 用户在官方页面正常输入账号密码/短信验证码；
 * 3. 自动嗅探 CookieManager，一旦捕获到 sessionid/token 等鉴权凭据即刻自动保存并关闭弹窗；
 * 4. 遵守直角设计规范 (RectangleShape)。
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
    var loginCaptured by remember { mutableStateOf(false) }

    fun checkAndSaveCookies(fromUrl: String): Boolean {
        if (loginCaptured) return true
        val cm = CookieManager.getInstance()
        val cookie = cm.getCookie(fromUrl) ?: cm.getCookie(watchDomain) ?: ""
        if (cookie.isBlank()) return false

        val isLogin = when (platform) {
            Platform.INSTAGRAM -> cookie.contains("sessionid=") || cookie.contains("ds_user_id=")
            Platform.XIAOHONGSHU -> cookie.contains("web_session=") || cookie.contains("a1=")
            Platform.FACEBOOK -> cookie.contains("c_user=") || cookie.contains("xs=")
            else -> cookie.length > 30
        }

        if (isLogin) {
            loginCaptured = true
            CookieStore.put(context, platform, cookie)
            Toast.makeText(context, "${platform.displayName} 登录成功，已自动保存登录凭据！", Toast.LENGTH_SHORT).show()
            scope.launch {
                delay(800)
                onCookieSaved(cookie)
                onDismiss()
            }
            return true
        }
        return false
    }

    // 后台轻量轮询嗅探（防某些单页应用 AJAX 登录不触发页面跳转）
    LaunchedEffect(platform) {
        while (!loginCaptured) {
            delay(1200)
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
                        Text(
                            text = "登录 ${platform.displayName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = if (loginCaptured) "检测到登录状态，正在完成…" else currentUrl.substringBefore('?').take(45),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (loginCaptured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = { webViewInstance?.reload() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Button(
                        onClick = {
                            val cm = CookieManager.getInstance()
                            val cookie = cm.getCookie(currentUrl) ?: cm.getCookie(watchDomain) ?: ""
                            if (cookie.isNotBlank()) {
                                CookieStore.put(context, platform, cookie)
                                Toast.makeText(context, "已保存当前 ${platform.displayName} 凭据", Toast.LENGTH_SHORT).show()
                                onCookieSaved(cookie)
                                onDismiss()
                            } else {
                                Toast.makeText(context, "尚未检测到 Cookie，请先在下方登录账号", Toast.LENGTH_SHORT).show()
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

                // 状态横幅
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (loginCaptured) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
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
                                "✓ 已成功捕获登录凭据，正在保存…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                "请在下方官方页面安全登录。登录成功后将自动捕获凭据，所有数据仅保存在本机。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // WebView 主体
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
                                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        loadProgress = newProgress / 100f
                                        isLoading = newProgress < 100
                                    }
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        url?.let {
                                            currentUrl = it
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
                }
            }
        }
    }
}

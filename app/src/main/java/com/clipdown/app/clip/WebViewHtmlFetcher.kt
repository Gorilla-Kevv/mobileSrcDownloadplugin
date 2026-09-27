package com.clipdown.app.clip

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.clipdown.app.data.CookieStore
import com.clipdown.parser.model.Platform
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView 渲染抓取：真浏览器栈执行页面（过阿里云 WAF 指纹挑战 / Instagram JS 壳渲染），
 * 页面加载完成后回传 document.outerHTML。阻塞调用，必须在子线程使用。
 *
 * Cookie 注入：把 CookieStore 的网页 Cookie 逐条写入 WebView CookieManager，
 * 使 Instagram / 小红书以登录态渲染页面。
 */
object WebViewHtmlFetcher {

    private const val TAG = "WebViewFetch"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** 页面中是否已出现媒体数据的探针（IG React 异步填充后命中） */
    private const val MEDIA_PRESENT_JS =
        "(function(){var h=document.documentElement.outerHTML;" +
            "return h.indexOf('video_url')>-1||h.indexOf('playable_url')>-1||" +
            "h.indexOf('og:video')>-1||!!document.querySelector('video');})()"

    /** 平台 → Cookie 作用域名 */
    private val cookieDomains = mapOf(
        "instagram.com" to Platform.INSTAGRAM,
        "xiaohongshu.com" to Platform.XIAOHONGSHU
    )

    fun fetch(context: Context, url: String, timeoutMs: Long = 40_000L): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        android.util.Log.d(TAG, "fetch 开始：$url")
        val latch = CountDownLatch(1)
        var html: String? = null
        val appContext = context.applicationContext
        var wv: WebView? = null
        var cookieHeader: String? = null
        Handler(Looper.getMainLooper()).post {
            try {
                cookieHeader = injectCookies(appContext, url)
                @SuppressLint("SetJavaScriptEnabled")
                val view = WebView(appContext)
                wv = view
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    blockNetworkImage = true          // 不加载图片，加速出 HTML
                    userAgentString = DESKTOP_UA
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String) {
                        android.util.Log.d(TAG, "onPageFinished：$u")
                        startPolling(v) { page ->
                            if (html == null) {
                                html = page
                                latch.countDown()
                            }
                        }
                    }

                    override fun onReceivedError(v: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                        android.util.Log.d(TAG, "onReceivedError：${req.url} ${err.description}")
                    }
                }
                // 主请求直接带 Cookie 请求头（比 CookieManager 时序更可靠），双保险
                if (cookieHeader.isNullOrBlank()) view.loadUrl(url)
                else view.loadUrl(url, mapOf("Cookie" to cookieHeader!!))
                Handler(Looper.getMainLooper()).postDelayed({
                    if (latch.count != 0L) {
                        android.util.Log.d(TAG, "fetch 超时")
                        latch.countDown()
                    }
                }, timeoutMs)
            } catch (t: Throwable) {
                android.util.Log.d(TAG, "fetch 主线程异常：${t.message}")
                latch.countDown()
            }
        }
        latch.await(timeoutMs + 3000, TimeUnit.MILLISECONDS)
        wv?.let { v -> Handler(Looper.getMainLooper()).post { v.destroy() } }
        android.util.Log.d(TAG, "fetch 结束：html=${html?.length ?: "null"}")
        return html
    }

    /** 独立于页面回调的无状态轮询：每 2.5s 直接抓 outerHTML，命中媒体标记即回调；超时兜底 */
    private fun startPolling(v: WebView, onResult: (String) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val task = object : Runnable {
            var attempt = 0
            override fun run() {
                attempt++
                v.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                    val page = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                    if (page != null) {
                        val hasMedia = page.contains("video_url") || page.contains("playable_url") ||
                            page.contains("og:video") || page.contains("<video")
                        val title = Regex("""<title[^>]*>([^<]{0,80})""").find(page)?.groupValues?.getOrNull(1)
                        android.util.Log.d(TAG, "轮询 $attempt：len=${page.length} media=$hasMedia title=$title")
                        if (hasMedia) {
                            onResult(page)
                            return@evaluateJavascript
                        }
                    }
                    if (attempt <= 14) h.postDelayed(this, 2500)
                }
            }
        }
        h.postDelayed(task, 2000)
    }

    /** 返回拼好的 Cookie 请求头（同时写入 CookieManager 供页面内 XHR 使用） */
    private fun injectCookies(context: Context, url: String): String? {
        val cm = CookieManager.getInstance()
        var header: String? = null
        cookieDomains.forEach { (domain, platform) ->
            if (!url.contains(domain)) return@forEach
            val cookie = CookieStore.get(context, platform) ?: return@forEach
            header = cookie
            cookie.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
                cm.setCookie("https://$domain/", "$pair; Path=/; Domain=$domain")
            }
        }
        cm.flush()
        android.util.Log.d(TAG, "Cookie 注入：${header?.length ?: 0} 字符")
        return header
    }
}

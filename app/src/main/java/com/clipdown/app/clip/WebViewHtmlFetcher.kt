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

    fun fetch(context: Context, url: String, timeoutMs: Long = 25_000L): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        android.util.Log.d(TAG, "fetch 开始：$url")
        val latch = CountDownLatch(1)
        var html: String? = null
        val appContext = context.applicationContext
        var wv: WebView? = null
        Handler(Looper.getMainLooper()).post {
            try {
                injectCookies(appContext, url)
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
                    private var done = false
                    override fun onPageFinished(v: WebView, u: String) {
                        android.util.Log.d(TAG, "onPageFinished：$u")
                        if (done) return
                        // React 应用在 finished 之后才异步拉媒体数据，轮询等待出现再抓全量
                        fun poll(attempt: Int) {
                            if (done) return
                            v.evaluateJavascript(MEDIA_PRESENT_JS) { flag ->
                                if (done) return@evaluateJavascript
                                if (flag?.contains("true") == true) {
                                    done = true
                                    android.util.Log.d(TAG, "媒体数据出现（第 ${attempt + 1} 轮）")
                                    v.evaluateJavascript("document.documentElement.outerHTML") { h ->
                                        html = runCatching { JSONTokener(h).nextValue() as? String }.getOrNull()
                                        android.util.Log.d(TAG, "outerHTML 长度=${html?.length}")
                                        latch.countDown()
                                    }
                                } else if (attempt >= 8) {
                                    done = true
                                    android.util.Log.d(TAG, "轮询 8 轮未见媒体数据，按当前页面返回")
                                    v.evaluateJavascript("document.documentElement.outerHTML") { h ->
                                        html = runCatching { JSONTokener(h).nextValue() as? String }.getOrNull()
                                        latch.countDown()
                                    }
                                } else {
                                    v.postDelayed({ poll(attempt + 1) }, 2500)
                                }
                            }
                        }
                        poll(0)
                    }

                    override fun onReceivedError(v: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                        android.util.Log.d(TAG, "onReceivedError：${req.url} ${err.description}")
                    }
                }
                view.loadUrl(url)
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

    private fun injectCookies(context: Context, url: String) {
        val cm = CookieManager.getInstance()
        cookieDomains.forEach { (domain, platform) ->
            if (!url.contains(domain)) return@forEach
            val cookie = CookieStore.get(context, platform) ?: return@forEach
            cookie.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
                cm.setCookie("https://$domain/", "$pair; Path=/; Domain=$domain")
            }
        }
        cm.flush()
    }
}

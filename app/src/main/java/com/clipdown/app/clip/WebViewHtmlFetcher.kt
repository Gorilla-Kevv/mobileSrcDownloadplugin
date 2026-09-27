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

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** 平台 → Cookie 作用域名 */
    private val cookieDomains = mapOf(
        "instagram.com" to Platform.INSTAGRAM,
        "xiaohongshu.com" to Platform.XIAOHONGSHU
    )

    fun fetch(context: Context, url: String, timeoutMs: Long = 25_000L): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
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
                        if (done) return
                        done = true
                        v.evaluateJavascript("document.documentElement.outerHTML") { h ->
                            html = runCatching { JSONTokener(h).nextValue() as? String }.getOrNull()
                            latch.countDown()
                        }
                    }
                }
                view.loadUrl(url)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (latch.count != 0L) latch.countDown()
                }, timeoutMs)
            } catch (t: Throwable) {
                latch.countDown()
            }
        }
        latch.await(timeoutMs + 3000, TimeUnit.MILLISECONDS)
        wv?.let { v -> Handler(Looper.getMainLooper()).post { v.destroy() } }
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

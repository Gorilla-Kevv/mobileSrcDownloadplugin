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

    /**
     * 移动端 UA。小红书**对桌面 UA 的笔记页一律 302 到 /login（登录墙）**，
     * 移动端 UA 才直接返回带 SSR 数据（`__INITIAL_STATE__` + `imageList`）的页面。
     *
     * 这里刻意用 **Android Chrome** 而非 iPhone Safari：WebView 的 client hints
     * （Sec-CH-UA / Sec-CH-UA-Platform）本身就报 Android Chrome，UA 与提示不一致
     * 是典型爬虫特征，实测 iPhone UA 仍被跳登录墙（2026-10 对照）。
     * 版本号与模拟器 WebView 实际版本（113.0.5672）保持一致。
     */
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Mobile Safari/537.36"

    /** 按站点选择 UA：小红书走移动端，其余保持桌面端 */
    private fun uaFor(url: String): String =
        if (url.contains("xiaohongshu.com") || url.contains("xhslink.")) MOBILE_UA else DESKTOP_UA

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
        var finalUrl: String? = null
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
                    userAgentString = uaFor(url)
                }
                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String) {
                        android.util.Log.d(TAG, "onPageFinished：$u")
                        startPolling(v) { page ->
                            if (html == null) {
                                html = page
                                finalUrl = v.url      // 落最终 URL：小红书跳首页/登录页时靠它取证
                                latch.countDown()
                            }
                        }
                    }

                    override fun onReceivedError(v: WebView, req: android.webkit.WebResourceRequest, err: android.webkit.WebResourceError) {
                        android.util.Log.d(TAG, "onReceivedError：${req.url} ${err.description}")
                    }
                }
                // 主请求带 Cookie 与 Referer（比 CookieManager 时序更可靠），双保险。
                // Referer 对小红书是必需的自然度信号：缺失时更容易被判定为机器请求
                val extra = buildMap {
                    if (!cookieHeader.isNullOrBlank()) put("Cookie", cookieHeader!!)
                    put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    if (url.contains("xiaohongshu.com")) put("Referer", "https://www.xiaohongshu.com/")
                }
                view.loadUrl(url, extra)
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
        android.util.Log.d(TAG, "fetch 结束：html=${html?.length ?: "null"} finalUrl=$finalUrl")
        // 调试落盘：保留最后一次渲染页，供 adb pull 分析（应用私有外部目录，无需存储权限）
        html?.let { page ->
            runCatching {
                val dir = appContext.getExternalFilesDir(null) ?: return@let
                java.io.File(dir, "debug_last_page.html").writeText(page)
            }
        }
        return html
    }

    /**
     * 独立于页面回调的无状态轮询：每 2.5s 直接抓 outerHTML，命中媒体标记即回调。
     *
     * 修复（小红书链路实测）：原实现**只在命中视频标记时才回调**，
     * 而小红书图集页根本没有视频标记（媒体在 `__INITIAL_STATE__` 的 imageList 里），
     * 于是必然白等 40s 超时、html=null，再退回被阿里云 WAF 拦掉的 OkHttp → 图集永远解析失败。
     * 现在改为三种收口：
     *  1. 命中媒体标记 —— 立即返回（IG 视频页原路径不变）
     *  2. 页面长度连续两次不再变化 —— 判定已渲染稳定，提前返回（省掉几十秒干等）
     *  3. 兜底：最后一次轮询无论如何都把当前页面交出去，不再丢弃
     */
    private fun startPolling(v: WebView, onResult: (String) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val task = object : Runnable {
            var attempt = 0
            var lastLen = -1
            var stable = 0
            override fun run() {
                attempt++
                v.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                    val page = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                    if (page != null) {
                        val hasMedia = page.contains("video_versions") || page.contains("video_url") ||
                            page.contains("playable_url") || page.contains("og:video") || page.contains("<video")
                        val title = Regex("""<title[^>]*>([^<]{0,80})""").find(page)?.groupValues?.getOrNull(1)
                        stable = if (page.length == lastLen) stable + 1 else 0
                        lastLen = page.length
                        android.util.Log.d(
                            TAG,
                            "轮询 $attempt：len=${page.length} media=$hasMedia stable=$stable title=$title"
                        )
                        when {
                            hasMedia -> {
                                onResult(page)
                                return@evaluateJavascript
                            }
                            // 已渲染稳定（长度不再变化）→ 提前收口，不再死等超时
                            stable >= 2 && attempt >= 3 -> {
                                android.util.Log.d(TAG, "页面已稳定，提前返回渲染结果")
                                onResult(page)
                                return@evaluateJavascript
                            }
                        }
                    }
                    if (attempt <= 14) h.postDelayed(this, 2500)
                    else page?.let { onResult(it) }   // 兜底：超时前把最后一次页面交出去
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

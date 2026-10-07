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

    /**
     * @param scrollTimes 页面渲染稳定后再滚到底部的次数（>0 时用于触发"无限滚动"加载更多）
     * @param desktop 强制桌面 UA：小红书**主页**的 SSR/无限滚动 DOM 只在桌面端下发
     *                （移动端 Web 渲染的 reds-note-card 里根本没有笔记 ID，无法提取）
     */
    fun fetch(
        context: Context,
        url: String,
        timeoutMs: Long = 40_000L,
        scrollTimes: Int = 0,
        desktop: Boolean = false
    ): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        android.util.Log.d(TAG, "fetch 开始：$url scrollTimes=$scrollTimes desktop=$desktop")
        // 滚动加载要额外时间：渲染 timeoutMs + 每轮阶梯扫动约 8s + 滚动后稳定轮询预算
        val budget = timeoutMs + if (scrollTimes > 0) scrollTimes * 8_000L + 20_000L else 0L
        val latch = CountDownLatch(1)
        var html: String? = null
        var polled = false
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
                // 必须给一个真实视口：未附加到窗口的 WebView 尺寸为 0，页面里
                // window.innerHeight / documentElement.clientHeight 都是 0，
                // 基于视口的 IntersectionObserver 永不触发 → 主页虚拟列表不渲染后续页，
                // 分页接口也就停在初始预取的那两三页。手动 measure+layout 即可拿到真实视口。
                val dm = appContext.resources.displayMetrics
                view.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(dm.widthPixels, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(dm.heightPixels, android.view.View.MeasureSpec.EXACTLY)
                )
                view.layout(0, 0, dm.widthPixels, dm.heightPixels)
                android.util.Log.d(TAG, "视口注入：${dm.widthPixels}x${dm.heightPixels} measured=${view.measuredWidth}x${view.measuredHeight}")
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    blockNetworkImage = true          // 不加载图片，加速出 HTML
                    userAgentString = if (desktop) DESKTOP_UA else uaFor(url)
                }
                view.webViewClient = object : WebViewClient() {
                    /**
                     * 页面 JS 执行前注入 XHR 钩子：小红书主页分页接口带 x-s 签名、App 侧无法重放，
                     * 但**页面自己会发**并拿到响应——把 `user_posted` 的响应原文暂存到 window.__pages。
                     * 注意：evaluateJavascript 排在页面脚本之后，onPageStarted 里注入对**首屏**请求太晚，
                     * 但分页请求是滚动之后才发的，仍然赶得上（另见 startPolling 里的兜底注入）。
                     */
                    override fun onPageStarted(v: WebView, u: String, favicon: android.graphics.Bitmap?) {
                        if (u.contains("xiaohongshu.com")) installPageHook(v)
                    }

                    override fun onPageFinished(v: WebView, u: String) {
                        android.util.Log.d(TAG, "onPageFinished：$u")
                        startPolling(v) { page ->
                            if (polled) return@startPolling   // 轮询链可能并发回调，只认第一次收口
                            if (html == null) {
                                html = page
                                finalUrl = v.url      // 落最终 URL：小红书跳首页/登录页时靠它取证
                                if (scrollTimes > 0) {
                                    // 需要"加载更多"：继续滚到底部触发无限滚动，再回传更长的页面
                                    scrollToLoadMore(v, scrollTimes) { more ->
                                        html = more
                                        latch.countDown()
                                    }
                                } else {
                                    latch.countDown()
                                }
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
                }, budget)
            } catch (t: Throwable) {
                android.util.Log.d(TAG, "fetch 主线程异常：${t.message}")
                latch.countDown()
            }
        }
        latch.await(budget + 3000, TimeUnit.MILLISECONDS)
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

    /** 注入 XHR 钩子：把 `user_posted` 分页响应原文暂存到 `window.__pages`（幂等） */
    private fun installPageHook(v: WebView) {
        // 注意：这段 JS 每行拼接必须保持括号自平衡，改完请跑 `node scripts/check-inject-js.js` 校验语法
        v.evaluateJavascript(
            "(function(){" +
                "if(window.__pages){return;}" +
                "window.__pages=[];" +
                "var origOpen=XMLHttpRequest.prototype.open;" +
                "XMLHttpRequest.prototype.open=function(method,url){" +
                "try{" +
                "if(String(url).indexOf('user_posted')>-1){" +
                "var xhr=this;" +
                "xhr.addEventListener('load',function(){" +
                "if(xhr.status===200 && String(xhr.responseText).length>40){" +
                "window.__pages.push(xhr.responseText);" +
                "}" +
                "});" +
                "}" +
                "}catch(err){}" +
                "return origOpen.apply(this,arguments);" +
                "};" +
                "})()"
        ) {}
    }

    /**
     * 反复把 window 滚到底触发页面的无限滚动，回传 `渲染 DOM + 分页接口响应`。
     *
     * 小红书等平台的主页分页接口带 `x-s` 签名，App 侧无法重放；但**页面自己的 JS 能签名**。
     * 由于列表是虚拟滚动的（DOM 里始终只有约 30 张卡片），单看渲染结果拿不到后续页，
     * 因此用注入钩子把 `user_posted` 的响应原文（`<<<XHS_PAGES>>>` 分隔）一并回传，
     * 解析器优先吃接口 JSON，DOM 只作兜底。
     */
    private fun scrollToLoadMore(v: WebView, times: Int, onDone: (String) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        var i = 0
        val SEP = "<<<XHS_PAGES>>>"

        fun eval(js: String, cb: (String?) -> Unit) {
            v.evaluateJavascript(js) { raw ->
                cb(runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull())
            }
        }

        fun grab() {
            // 分页响应必须走 JSON 编码回传：若把它写进 DOM 再取 outerHTML，
            // HTML 序列化会把引号变成 &quot;，接口 JSON 就废了
            eval(
                "document.documentElement.outerHTML + '$SEP' + JSON.stringify(JSON.stringify(window.__pages||[]))"
            ) { page ->
                android.util.Log.d(TAG, "滚动加载完成：len=${page?.length}")
                onDone(page ?: "")
            }
        }

        /**
         * 阶梯式把 **window** 滚到底（顶 → 1/3 → 2/3 → 底）。
         *
         * 关键：拿到真实视口后**不能再给容器强制 maxHeight**——那样滚动模型就从
         * "整页 window 滚动"变成"容器内滚"，而页面自己的无限滚动监听挂在 window 上，
         * 结果一页都不会再请求（实测视口修好后 pages 反而从 3 掉到 1）。
         * 让容器保持自然高度、滚 window，才与真实浏览器一致。
         */
        fun scrollRound(done: () -> Unit) {
            // 分页请求由本次滚动触发，钩子必须在滚动前就位（onPageStarted 那次可能太晚，幂等补注入）
            installPageHook(v)

            fun rung(frac: String, label: String, next: () -> Unit) {
                eval(
                    "(function(){try{" +
                        "var de=document.documentElement;" +
                        "var y=Math.round(de.scrollHeight*($frac));" +
                        "window.scrollTo(0,y);de.scrollTop=y;" +
                        "return '$label y='+Math.round(window.scrollY)+' docH='+de.scrollHeight+" +
                        "' items='+document.querySelectorAll('.note-item').length+" +
                        "' pages='+(window.__pages||[]).length+' ih='+window.innerHeight;" +
                        "}catch(e){return 'ERR:'+e.message;}})()"
                ) { info ->
                    android.util.Log.d(TAG, "滚动 $i/$times [$info]")
                    h.postDelayed(next, 1200)
                }
            }
            rung("0", "顶") {
                rung("0.34", "1/3") {
                    rung("0.67", "2/3") {
                        rung("1", "底") { done() }
                    }
                }
            }
        }

        fun step() {
            if (i++ >= times) {
                // 新数据是异步到达的：轮询到「分页响应数」连续两次不再增长再收口
                var attempt = 0
                var lastPages = -1
                var stable = 0
                val poll = object : Runnable {
                    override fun run() {
                        attempt++
                        eval("String((window.__pages||[]).length)") { n ->
                            val pages = n?.toIntOrNull() ?: 0
                            stable = if (pages == lastPages) stable + 1 else 0
                            lastPages = pages
                            android.util.Log.d(TAG, "滚动后轮询 $attempt：pages=$pages stable=$stable")
                            // 注意：evaluateJavascript 对数字返回不带引号的字面量，
                            // 必须 String() 包一层，否则 as? String 恒为 null → 轮询形同虚设、提前收口
                            if ((stable >= 2 && attempt >= 4) || attempt >= 14) grab()
                            else h.postDelayed(this, 2500)
                        }
                    }
                }
                h.postDelayed(poll, 2500)
                return
            }
            scrollRound { h.postDelayed({ step() }, 2500) }
        }
        step()
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

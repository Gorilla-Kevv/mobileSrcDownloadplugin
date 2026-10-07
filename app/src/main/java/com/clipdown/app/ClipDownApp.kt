package com.clipdown.app

import android.app.Application
import android.util.Log
import com.clipdown.app.data.CookieStore
import com.clipdown.app.data.SettingsRepository
import com.clipdown.app.clip.ClipboardMonitor
import com.clipdown.app.clip.WebViewHtmlFetcher
import com.clipdown.downloader.DownloadController
import com.clipdown.downloader.model.DownloadConfig
import com.clipdown.parser.core.ParserEngine
import com.clipdown.app.update.UpdateCenter
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ClipDownApp : Application() {

    /** 应用级协程作用域：与进程同生命周期，承载解析与守护任务 */
    val appScope = MainScope()

    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)

        // 解析内核：登录态由 CookieStore 提供，内核本身不持有 Android 依赖
        ParserEngine.bootstrap(
            androidContext = this,
            config = settings.parserConfig(),
            cookieProvider = { platform -> CookieStore.get(this, platform) },
            logger = { tag, msg -> Log.d(tag, msg) },
            webFetcher = { url -> WebViewHtmlFetcher.fetch(this, url) },
            // 主页"加载更多"：平台分页接口需要签名，改为让页面自己的 JS 在 WebView 里滚动加载
            webFetcherScroll = { url, times, desktop ->
                WebViewHtmlFetcher.fetch(this, url, timeoutMs = 90_000L, scrollTimes = times, desktop = desktop)
            }
        )

        // 下载引擎：并发度与网络策略在设置变更后通过 updateConfig 热更新
        val snapshot = runBlocking { settings.snapshot() }
        DownloadController.install(
            context = this,
            config = DownloadConfig(
                maxConcurrent = snapshot.maxConcurrent,
                wifiOnly = snapshot.wifiOnly
            )
        )

        ClipboardMonitor.install(this)

        // 应用内更新：安装到公开分发仓库（GitHub Release 固定链接，见 gradle.properties）
        UpdateCenter.install(this)

        // 主页标签：恢复上次打开的博主主页（结果与勾选落盘，进程回收也不丢）
        com.clipdown.app.ui.profile.ProfileCenter.install(this)

        appScope.launch {
            settings.maxConcurrent.collect { max ->
                val wifi = settings.wifiOnly.first()
                DownloadController.updateConfig(DownloadConfig(maxConcurrent = max, wifiOnly = wifi))
            }
        }
        appScope.launch {
            settings.remoteEndpoint.collect { ParserEngine.updateConfig(settings.parserConfig()) }
        }
    }

    companion object {
        @Volatile
        private var instance: ClipDownApp? = null

        fun get(): ClipDownApp = instance ?: error("ClipDownApp 尚未初始化")
    }
}

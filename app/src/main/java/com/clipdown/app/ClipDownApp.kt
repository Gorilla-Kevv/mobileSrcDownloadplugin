package com.clipdown.app

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
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

class ClipDownApp : Application(), ImageLoaderFactory {

    /** 应用级协程作用域：与进程同生命周期，承载解析与守护任务 */
    val appScope = MainScope()

    lateinit var settings: SettingsRepository
        private set

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

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
                wifiOnly = snapshot.wifiOnly,
                saveToAlbum = snapshot.saveToAlbum,
                saveSubDir = snapshot.saveSubDir
            )
        )

        ClipboardMonitor.install(this)

        // 应用内更新：安装到公开分发仓库（GitHub Release 固定链接，见 gradle.properties）
        UpdateCenter.install(this)

        // 主页标签：恢复上次打开的博主主页（结果与勾选落盘，进程回收也不丢）
        com.clipdown.app.ui.profile.ProfileCenter.install(this)

        // 跨端同步：局域网配对与多端数据中枢初始化
        com.clipdown.app.sync.SyncManager.init(this)

        // 设置热更新：任一存储相关项变化都重建配置，避免"改了设置不生效"。
        // 阶段 84 之前 saveToAlbum / 子目录根本没接到引擎上，导致开关形同虚设。
        appScope.launch {
            kotlinx.coroutines.flow.combine(
                settings.maxConcurrent,
                settings.wifiOnly,
                settings.saveToAlbum,
                settings.saveSubDir
            ) { max, wifi, album, subDir ->
                DownloadConfig(
                    maxConcurrent = max,
                    wifiOnly = wifi,
                    saveToAlbum = album,
                    saveSubDir = subDir
                )
            }.collect { cfg ->
                DownloadController.updateConfig(cfg)
            }
        }
        // 阶段 84：一次性清理升级前遗留的「相册 + 私有目录」双份文件，回收空间。
        // 只在相册那份确认可读时删私有副本，避免删掉唯一实体。
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val freed = runCatching { DownloadController.engine().cleanupLegacyDualCopies() }
                .getOrDefault(0L)
            if (freed > 0) {
                Log.i("ClipDownApp", "已回收历史双份文件 ${freed / 1024 / 1024} MB")
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

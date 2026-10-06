package com.clipdown.app.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.clipdown.app.update.UpdateCenter
import com.clipdown.downloader.DownloadController
import com.clipdown.downloader.DownloadService
import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.PostKind
import com.clipdown.parser.model.ProfilePost
import com.clipdown.parser.model.ProfileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 主页页面的状态 */
sealed interface ProfileUiState {
    data object Idle : ProfileUiState

    data class Loading(val url: String) : ProfileUiState

    data class Loaded(
        val result: ProfileResult,
        /** 已勾选的笔记 id */
        val selected: Set<String> = emptySet(),
        /** 类型筛选：null = 全部 */
        val filter: PostKind? = null,
        /** 批量下载进行中的进度文案 */
        val progress: String? = null
    ) : ProfileUiState {
        /** 当前筛选下可见的笔记 */
        val visible: List<ProfilePost> get() = filter?.let { f -> result.posts.filter { it.kind == f } } ?: result.posts
    }

    data class Failed(val url: String, val message: String) : ProfileUiState
}

/**
 * 主页功能的进程级状态中心。
 *
 * 与 [UpdateCenter] 同一思路：页面、入口（首页/气泡）共用一份状态，
 * 避免"入口解析一次、页面再解析一次"的重复请求。
 *
 * 下载策略（关键设计）：主页列表只有封面与标题，**媒体资源要点开单篇才拿得到**。
 * 因此"下载所选"= 逐篇走单篇解析链路取原图/原视频，再交给下载引擎，
 * 并以 `sourceUrl = 该笔记链接` 入队 —— 下载页会自动按笔记分组展示。
 */
object ProfileCenter {

    /** 并发解析笔记数（太大会被平台限速） */
    private const val PARSE_CONCURRENCY = 2

    var state: ProfileUiState by mutableStateOf(ProfileUiState.Idle)
        private set

    /** 最近一次打开的主页链接（返回后再进来可直接复用） */
    var lastUrl: String? by mutableStateOf(null)
        private set

    /** 从入口触发加载（幂等：同一链接且已加载则不重复请求） */
    suspend fun open(url: String, force: Boolean = false) {
        val cur = state
        if (!force && lastUrl == url && cur is ProfileUiState.Loaded) return
        lastUrl = url
        load(url)
    }

    suspend fun load(url: String) {
        state = ProfileUiState.Loading(url)
        val r = withContext(Dispatchers.IO) { runCatching { ParserEngine.parseProfile(url) } }
        state = if (r.isSuccess) {
            ProfileUiState.Loaded(r.getOrThrow())
        } else {
            val e = r.exceptionOrNull()
            ProfileUiState.Failed(url, (e as? ParseException)?.message ?: e?.message ?: "主页解析失败")
        }
    }

    fun retry() {
        lastUrl?.let { url -> state = ProfileUiState.Idle }
    }

    /**
     * 注入演示数据。
     *
     * 用途：主页解析依赖平台前端渲染，在网络受限（如国内直连访问不了目标站）
     * 或未配置 Cookie 时拿不到真实数据，但仍需走查/截图页面版式。
     * 入口：`adb shell am start -n com.clipdown.app/.ui.MainActivity --ez demo_profile true`
     */
    fun injectDemo() {
        val demo = ProfileResult(
            platform = com.clipdown.parser.model.Platform.XIAOHONGSHU,
            resolverId = "demo",
            sourceUrl = "https://www.xiaohongshu.com/user/profile/demo",
            userId = "demo",
            nickname = "折纸",
            bio = "画点小樱，偶尔发发 cos，更新很慢",
            stats = com.clipdown.parser.model.ProfileStats(
                posts = 42, followers = 12000, following = 128, likes = 3456
            ),
            posts = (1..8).map { i ->
                ProfilePost(
                    id = "demo$i",
                    url = "https://www.xiaohongshu.com/explore/demo$i",
                    title = when (i % 3) {
                        0 -> "今天穿这套去约会怎么样？第 $i 篇"
                        1 -> "搬一下之前画过的小樱（$i）"
                        else -> "🇲🇴 澳门随拍 · 第 $i 篇"
                    },
                    kind = if (i % 3 == 0) PostKind.VIDEO else PostKind.ALBUM,
                    mediaCount = if (i % 3 == 0) null else i % 5 + 1
                )
            },
            warning = "这是演示数据（未联网解析），用于走查页面版式"
        )
        lastUrl = demo.sourceUrl
        state = ProfileUiState.Loaded(demo)
    }

    // ---- 选择 ----

    private fun mutateLoaded(block: (ProfileUiState.Loaded) -> ProfileUiState.Loaded) {
        val cur = state as? ProfileUiState.Loaded ?: return
        state = block(cur)
    }

    fun toggle(id: String) = mutateLoaded { s ->
        s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    fun setFilter(filter: PostKind?) = mutateLoaded { s ->
        // 切筛选不清空已选（用户可能在多个类型间挑），但只对可见项生效的操作由 UI 控制
        s.copy(filter = filter)
    }

    fun selectAllVisible() = mutateLoaded { s -> s.copy(selected = s.selected + s.visible.map { it.id }) }

    fun clearSelection() = mutateLoaded { s -> s.copy(selected = emptySet()) }

    // ---- 下载 ----

    /**
     * 下载所选笔记：逐篇解析后入队。
     * 逐篇解析可能较慢，用 [ProfileUiState.Loaded.progress] 回显进度。
     */
    suspend fun downloadSelected(context: android.content.Context) {
        val s = state as? ProfileUiState.Loaded ?: return
        val targets = s.result.posts.filter { it.id in s.selected }
        if (targets.isEmpty()) return

        var done = 0
        var ok = 0
        var failed = 0
        mutateLoaded { it.copy(progress = "正在解析 0/${targets.size}") }

        targets.chunked(PARSE_CONCURRENCY).forEach { batch ->
            batch.forEach { post ->
                val r = withContext(Dispatchers.IO) { ParserEngine.parseSafe(post.url) }
                val parsed = r.getOrNull()
                if (parsed != null && !parsed.isEmpty) {
                    DownloadController.enqueueAll(parsed.media, parsed.platform, parsed.title, post.url)
                    ok++
                } else {
                    failed++
                }
                done++
                mutateLoaded { it.copy(progress = "正在解析 $done/${targets.size}") }
            }
        }

        context.startService(DownloadService.intent(context, DownloadService.ACTION_RESUME))
        mutateLoaded {
            it.copy(
                progress = if (failed == 0) "已加入下载队列：$ok 篇" else "已加入 $ok 篇，$failed 篇解析失败",
                selected = emptySet()
            )
        }
    }

    /** 下载单篇（详情面板用） */
    suspend fun downloadSingle(context: android.content.Context, post: ProfilePost) {
        val r = withContext(Dispatchers.IO) { ParserEngine.parseSafe(post.url) }
        val parsed = r.getOrNull()
        if (parsed != null && !parsed.isEmpty) {
            DownloadController.enqueueAll(parsed.media, parsed.platform, parsed.title, post.url)
            context.startService(DownloadService.intent(context, DownloadService.ACTION_RESUME))
        }
    }

    fun clearProgress() = mutateLoaded { it.copy(progress = null) }
}

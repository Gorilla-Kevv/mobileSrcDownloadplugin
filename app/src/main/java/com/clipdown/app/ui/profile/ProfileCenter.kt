package com.clipdown.app.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.clipdown.downloader.DownloadController
import com.clipdown.downloader.DownloadService
import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.model.ParseException
import com.clipdown.parser.model.PostKind
import com.clipdown.parser.model.ProfilePost
import com.clipdown.parser.model.ProfileResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单个主页标签页的内容状态 */
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
        val visible: List<ProfilePost>
            get() = filter?.let { f -> result.posts.filter { it.kind == f } } ?: result.posts
    }

    data class Failed(val url: String, val message: String) : ProfileUiState
}

/**
 * 一个「主页标签页」。
 *
 * 对应浏览器的一个 tab：保留自己的解析结果、勾选与筛选状态，
 * 切换标签不重新请求、也不丢已选内容。
 */
data class ProfileSession(
    val url: String,
    val state: ProfileUiState,
    /** 标签上显示的名字（解析成功后取博主昵称） */
    val title: String
)

/**
 * 主页功能的进程级状态中心（**多标签**）。
 *
 * 设计要点：
 * - `sessions` 是保序的标签列表，`activeUrl` 指向当前查看的标签；
 * - 从首页解析主页、或气泡点「打开主页」，都是 `open(url)`：
 *   已在列表里就切过去（**不重复请求**），否则新建标签并加载；
 * - 每个标签的状态（结果/勾选/筛选）独立保存，所以"回首页复制别的链接"
 *   不会影响已打开的主页；解析多个主页即多个标签，可随时切换查看与下载；
 * - 标签是**进程内**状态：应用被杀后清空（如需跨重启保留需落库，当前不做）。
 */
object ProfileCenter {

    /** 并发解析笔记数（太大会被平台限速） */
    private const val PARSE_CONCURRENCY = 2

    /** 最多同时保留的标签数（超出时关掉最旧的） */
    private const val MAX_SESSIONS = 8

    private const val PERSIST_FILE = "profile_sessions.json"

    var sessions: List<ProfileSession> by mutableStateOf(emptyList())
        private set

    var activeUrl: String? by mutableStateOf(null)
        private set

    val active: ProfileSession?
        get() = sessions.firstOrNull { it.url == activeUrl }

    /** 最近一次打开的主页链接（空态页据此提示/重试） */
    val lastUrl: String? get() = sessions.lastOrNull()?.url

    // ────────────────────────── 持久化 ──────────────────────────
    //
    // 标签是"用户辛苦等来的解析结果"，进程被系统回收后不该丢：
    // 把每个标签的结果与勾选落到 filesDir 下的 JSON，下次启动恢复。

    @Serializable
    private data class PersistedSession(
        val url: String,
        val title: String,
        val result: ProfileResult,
        val selected: List<String> = emptyList()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var storeFile: File? = null

    fun install(context: android.content.Context) {
        storeFile = File(context.filesDir, PERSIST_FILE)
        restore()
    }

    private fun restore() {
        val f = storeFile ?: return
        if (!f.exists()) return
        runCatching {
            val list = json.decodeFromString<List<PersistedSession>>(f.readText())
            val restored = list.takeLast(MAX_SESSIONS).map { p ->
                ProfileSession(
                    url = p.url,
                    state = ProfileUiState.Loaded(p.result, p.selected.toSet()),
                    title = p.title.ifBlank { p.result.displayName }
                )
            }
            if (restored.isNotEmpty()) {
                sessions = restored
                activeUrl = restored.last().url
            }
        }
    }

    private fun persist() {
        val f = storeFile ?: return
        runCatching {
            val list = sessions.mapNotNull { s ->
                val st = s.state as? ProfileUiState.Loaded ?: return@mapNotNull null
                PersistedSession(s.url, s.title, st.result, st.selected.toList())
            }
            f.writeText(json.encodeToString(list))
        }
    }

    // ────────────────────────── 标签管理 ──────────────────────────

    /** 打开（或切换）一个主页标签 */
    suspend fun open(url: String, force: Boolean = false) {
        val existing = sessions.firstOrNull { it.url == url }
        activeUrl = url
        if (existing != null && !force && existing.state !is ProfileUiState.Failed) return
        load(url)
    }

    fun switchTo(url: String) {
        if (sessions.any { it.url == url }) activeUrl = url
    }

    fun close(url: String) {
        val idx = sessions.indexOfFirst { it.url == url }
        if (idx < 0) return
        val next = sessions.toMutableList().also { it.removeAt(idx) }
        sessions = next
        if (activeUrl == url) {
            activeUrl = next.getOrNull(idx.coerceAtMost(next.size - 1))?.url
        }
        persist()
    }

    fun closeAll() {
        sessions = emptyList()
        activeUrl = null
        persist()
    }

    /** 更新（或插入）某个标签 */
    private fun upsert(url: String, build: (ProfileSession?) -> ProfileSession) {
        val list = sessions.toMutableList()
        val i = list.indexOfFirst { it.url == url }
        val updated = build(if (i >= 0) list[i] else null)
        if (i >= 0) list[i] = updated else list.add(updated)
        // 超出上限时关掉最旧的（不影响当前活动标签）
        while (list.size > MAX_SESSIONS) {
            val victim = list.firstOrNull { it.url != activeUrl && it.url != url } ?: break
            list.remove(victim)
        }
        sessions = list
        persist()
    }

    private fun titleOf(state: ProfileUiState): String = when (state) {
        is ProfileUiState.Loaded -> state.result.displayName
        is ProfileUiState.Loading -> "加载中…"
        is ProfileUiState.Failed -> "打开失败"
        else -> "新标签"
    }

    // ────────────────────────── 加载 ──────────────────────────

    suspend fun load(url: String) {
        upsert(url) { ProfileSession(url, ProfileUiState.Loading(url), "加载中…") }
        val r = withContext(Dispatchers.IO) { runCatching { ParserEngine.parseProfile(url) } }
        val state = if (r.isSuccess) {
            ProfileUiState.Loaded(r.getOrThrow())
        } else {
            val e = r.exceptionOrNull()
            ProfileUiState.Failed(url, (e as? ParseException)?.message ?: e?.message ?: "主页解析失败")
        }
        upsert(url) { ProfileSession(url, state, titleOf(state)) }
    }

    /**
     * 直接采用已解析好的结果（气泡识别时已解析过一次，点「打开主页」直接展示，避免重复请求）。
     */
    fun adopt(url: String, result: ProfileResult) {
        val state = ProfileUiState.Loaded(result)
        upsert(url) { ProfileSession(url, state, titleOf(state)) }
        activeUrl = url
    }

    /**
     * 注入演示数据（无网络/无 Cookie 时走查版式用）。
     * `adb shell am start -n com.clipdown.app/.ui.MainActivity --ez demo_profile true`
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
        adopt(demo.sourceUrl, demo)
    }

    // ────────────────────────── 选择（作用于活动标签） ──────────────────────────

    private fun mutateActive(block: (ProfileUiState.Loaded) -> ProfileUiState.Loaded) {
        val url = activeUrl ?: return
        val cur = active?.state as? ProfileUiState.Loaded ?: return
        val next = block(cur)
        upsert(url) { ProfileSession(url, next, titleOf(next)) }
    }

    fun toggle(id: String) = mutateActive { s ->
        s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    fun setFilter(filter: PostKind?) = mutateActive { s -> s.copy(filter = filter) }

    fun selectAllVisible() = mutateActive { s -> s.copy(selected = s.selected + s.visible.map { it.id }) }

    fun clearSelection() = mutateActive { s -> s.copy(selected = emptySet()) }

    // ────────────────────────── 下载 ──────────────────────────

    /**
     * 下载活动标签里所选的笔记：逐篇解析后入队。
     * 主页列表只有封面与标题，**媒体要点开单篇才拿得到**，故这里逐篇走单篇链路；
     * 以 `sourceUrl = 该笔记链接` 入队，下载页会自动按笔记分组。
     */
    suspend fun downloadSelected(context: android.content.Context) {
        val s = active?.state as? ProfileUiState.Loaded ?: return
        val targets = s.result.posts.filter { it.id in s.selected }
        if (targets.isEmpty()) return

        var done = 0
        var ok = 0
        var failed = 0
        mutateActive { it.copy(progress = "正在解析 0/${targets.size}") }

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
                mutateActive { it.copy(progress = "正在解析 $done/${targets.size}") }
            }
        }

        context.startService(DownloadService.intent(context, DownloadService.ACTION_RESUME))
        mutateActive {
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

    fun clearProgress() = mutateActive { it.copy(progress = null) }
}

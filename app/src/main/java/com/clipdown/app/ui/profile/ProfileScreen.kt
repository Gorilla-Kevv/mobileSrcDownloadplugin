package com.clipdown.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.clipdown.app.ui.components.AppCard
import com.clipdown.app.ui.components.AppChip
import com.clipdown.app.ui.components.EmptyState
import com.clipdown.app.ui.components.NoticeBar
import com.clipdown.app.ui.components.PrimaryButton
import com.clipdown.app.ui.components.StatusPill
import com.clipdown.app.ui.components.Tone
import com.clipdown.app.ui.components.VSpace
import com.clipdown.app.ui.theme.AppTheme
import com.clipdown.parser.core.ParserEngine
import com.clipdown.parser.model.PostKind
import com.clipdown.parser.model.ProfilePost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 博主主页页（独立新页面）。
 *
 * 结构（自上而下）：
 * 1. 顶栏：返回 + 博主名 + 刷新
 * 2. 博主信息卡：头像 / 昵称 / 平台 / 简介 / 笔记·粉丝·关注 三项统计
 * 3. 筛选行：全部 / 视频 / 图文 + 已选计数 + 全选·清空
 * 4. 笔记网格：两列，封面 + 选择圈 + 标题 + 类型角标
 * 5. 吸底操作条：已选 N 项 →「下载所选」（逐篇解析后入队）
 *
 * 点封面（非选择圈）打开 [NoteDetailSheet]：解析该篇后展示正文与媒体清单，可单独下载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = ProfileCenter.state
    var detail by remember { mutableStateOf<ProfilePost?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        (state as? ProfileUiState.Loaded)?.result?.displayName ?: "博主主页",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val url = ProfileCenter.lastUrl
                    if (url != null) {
                        IconButton(onClick = { scope.launch { ProfileCenter.load(url) } }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            val loaded = state as? ProfileUiState.Loaded
            if (loaded != null && loaded.selected.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(AppTheme.spacing.screen)
                ) {
                    loaded.progress?.let {
                        NoticeBar(it, Tone.Brand)
                        VSpace(AppTheme.spacing.sm)
                    }
                    PrimaryButton(
                        text = "下载所选 ${loaded.selected.size} 项",
                        icon = Icons.Default.Download,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = loaded.progress == null,
                        onClick = { scope.launch { ProfileCenter.downloadSelected(context) } }
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is ProfileUiState.Idle -> AppCard(Modifier.padding(AppTheme.spacing.screen)) {
                    EmptyState(
                        title = "还没有打开主页",
                        desc = "复制博主主页链接后点悬浮气泡，或在本页刷新",
                        icon = Icons.Default.Person
                    )
                }

                is ProfileUiState.Loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    VSpace(AppTheme.spacing.md)
                    Text("正在打开主页…", style = MaterialTheme.typography.bodySmall)
                    VSpace(AppTheme.spacing.xs)
                    Text(
                        "主页数据由平台前端渲染，首次打开可能需要十几秒",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is ProfileUiState.Failed -> Column(Modifier.padding(AppTheme.spacing.screen)) {
                    AppCard {
                        NoticeBar("打开失败：${s.message}", Tone.Danger)
                        VSpace(AppTheme.spacing.md)
                        Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                            PrimaryButton(
                                text = "重试",
                                icon = Icons.Default.Refresh,
                                modifier = Modifier.weight(1f),
                                onClick = { scope.launch { ProfileCenter.load(s.url) } }
                            )
                        }
                    }
                }

                is ProfileUiState.Loaded -> ProfileContent(
                    state = s,
                    onOpenDetail = { detail = it }
                )
            }
        }
    }

    detail?.let { post ->
        NoteDetailSheet(
            post = post,
            onDismiss = { detail = null },
            onDownload = { scope.launch { ProfileCenter.downloadSingle(context, post) } }
        )
    }
}

@Composable
private fun ProfileContent(state: ProfileUiState.Loaded, onOpenDetail: (ProfilePost) -> Unit) {
    val r = state.result
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(AppTheme.spacing.screen),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)
    ) {
        // ---- 博主信息卡 ----
        item(span = { GridItemSpan(maxLineSpan) }) {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = r.avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Spacer(Modifier.width(AppTheme.spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(r.displayName, style = MaterialTheme.typography.titleMedium)
                        VSpace(AppTheme.spacing.xs)
                        StatusPill(r.platform.displayName, Tone.Brand)
                    }
                }
                val bio = r.bio
                if (!bio.isNullOrBlank()) {
                    VSpace(AppTheme.spacing.md)
                    Text(
                        bio,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                VSpace(AppTheme.spacing.md)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                VSpace(AppTheme.spacing.sm)
                Row(Modifier.fillMaxWidth()) {
                    StatCell("笔记", r.stats?.posts, Modifier.weight(1f))
                    StatCell("粉丝", r.stats?.followers, Modifier.weight(1f))
                    StatCell("关注", r.stats?.following, Modifier.weight(1f))
                }
                val warn = r.warning
                if (!warn.isNullOrBlank()) {
                    VSpace(AppTheme.spacing.md)
                    NoticeBar(warn, Tone.Warning)
                }
            }
        }

        // ---- 筛选行 ----
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppChip("全部 ${r.posts.size}", state.filter == null) { ProfileCenter.setFilter(null) }
                    Spacer(Modifier.width(AppTheme.spacing.xs))
                    AppChip("视频", state.filter == PostKind.VIDEO) { ProfileCenter.setFilter(PostKind.VIDEO) }
                    Spacer(Modifier.width(AppTheme.spacing.xs))
                    AppChip("图文", state.filter == PostKind.IMAGE) { ProfileCenter.setFilter(PostKind.IMAGE) }
                    Spacer(Modifier.weight(1f))
                    if (state.selected.isNotEmpty()) {
                        StatusPill("已选 ${state.selected.size}", Tone.Brand)
                    }
                }
                VSpace(AppTheme.spacing.xs)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "共 ${state.visible.size} 篇",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { ProfileCenter.selectAllVisible() }) {
                        Text("全选", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = { ProfileCenter.clearSelection() }) {
                        Text("清空", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // ---- 笔记网格 ----
        items(state.visible, key = { it.id }) { post ->
            NoteTile(
                post = post,
                selected = post.id in state.selected,
                onToggle = { ProfileCenter.toggle(post.id) },
                onOpen = { onOpenDetail(post) }
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            VSpace(AppTheme.spacing.xl)
        }
    }
}

@Composable
private fun StatCell(label: String, value: Int?, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value?.let { formatCount(it) } ?: "—",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        VSpace(2.dp)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoteTile(
    post: ProfilePost,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit
) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.8f)
                .clip(RoundedCornerShape(AppTheme.radius.thumb))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onOpen)
        ) {
            AsyncImage(
                model = post.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // 类型角标
            val badge = when (post.kind) {
                PostKind.VIDEO -> "视频"
                PostKind.ALBUM -> "图集 ${post.mediaCount ?: ""}".trim()
                else -> null
            }
            if (badge != null) {
                Box(Modifier.padding(AppTheme.spacing.xs)) {
                    StatusPill(badge, Tone.Brand)
                }
            }
            // 选择圈（右上角，独立点击区，避免与"打开详情"冲突）
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = if (selected) "取消选择" else "选择",
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(AppTheme.spacing.xs)
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggle)
            )
        }
        VSpace(AppTheme.spacing.xs)
        Text(
            post.title ?: "（无标题）",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 笔记详情面板：打开时解析该篇，展示正文与媒体清单。
 *
 * 列表页只有封面与标题，**正文与媒体必须点开单篇才拿得到**，
 * 所以这里进入即触发一次单篇解析（复用作品页链路）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteDetailSheet(
    post: ProfilePost,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loading by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf(post.title) }
    var body by remember { mutableStateOf<String?>(null) }
    var media by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<Int>()) }

    LaunchedEffect(post.id) {
        val r = withContext(Dispatchers.IO) { ParserEngine.parseSafe(post.url) }
        loading = false
        val parsed = r.getOrNull()
        if (parsed != null && !parsed.isEmpty) {
            title = parsed.title ?: post.title
            body = parsed.description
            media = parsed.media.map { it.url }
            selected = parsed.media.indices.toSet()
        } else {
            error = (r.exceptionOrNull() as? com.clipdown.parser.model.ParseException)?.message
                ?: r.exceptionOrNull()?.message ?: "该篇解析失败"
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.spacing.screen)
                .padding(bottom = AppTheme.spacing.xl)
        ) {
            Text(title ?: "（无标题）", style = MaterialTheme.typography.titleMedium)
            VSpace(AppTheme.spacing.sm)

            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(AppTheme.spacing.sm))
                    Text("正在解析正文与媒体…", style = MaterialTheme.typography.bodySmall)
                }

                error != null -> NoticeBar(error!!, Tone.Danger)

                else -> {
                    if (!body.isNullOrBlank()) {
                        Text(
                            "正文",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        VSpace(AppTheme.spacing.xs)
                        Text(body!!, style = MaterialTheme.typography.bodySmall)
                        VSpace(AppTheme.spacing.md)
                    }
                    Text(
                        "媒体 ${media.size} 项（已选 ${selected.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    VSpace(AppTheme.spacing.sm)
                    media.forEachIndexed { i, u ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = if (i in selected) selected - i else selected + i
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (i in selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(AppTheme.spacing.sm))
                            Text(
                                u.substringAfterLast('/').take(40),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    VSpace(AppTheme.spacing.md)
                    Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.sm)) {
                        PrimaryButton(
                            text = "下载本篇",
                            icon = Icons.Default.Download,
                            modifier = Modifier.weight(1f),
                            onClick = onDownload
                        )
                        TextButton(onClick = { /* 由外部打开原链接 */ }) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("原链接")
                        }
                    }
                }
            }
        }
    }
}

/** 12000 → 1.2万 */
private fun formatCount(n: Int): String = when {
    n >= 100_000_000 -> "%.1f亿".format(n / 100_000_000.0)
    n >= 10_000 -> "%.1f万".format(n / 10_000.0)
    else -> n.toString()
}

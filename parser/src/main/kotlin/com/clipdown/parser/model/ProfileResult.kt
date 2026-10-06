package com.clipdown.parser.model

import kotlinx.serialization.Serializable

/** 博主主页的统计数字（拿不到时为 null，UI 显示 "—"） */
@Serializable
data class ProfileStats(
    val posts: Int? = null,
    val followers: Int? = null,
    val following: Int? = null,
    val likes: Int? = null
)

/** 主页列表里单篇笔记的形态 */
@Serializable
enum class PostKind {
    /** 视频 */
    VIDEO,

    /** 单图 */
    IMAGE,

    /** 多图/图集 */
    ALBUM,

    /** 未知（列表页拿不到类型时） */
    UNKNOWN
}

/**
 * 主页笔记列表里的一篇。
 *
 * 注意：[url] 是**笔记页链接**，点开详情/下载时会再走一次单篇解析拿原图与原视频；
 * 列表接口通常只给封面与标题，正文摘要放在 [excerpt]。
 */
@Serializable
data class ProfilePost(
    val id: String,
    val url: String,
    val title: String? = null,
    val cover: String? = null,
    val kind: PostKind = PostKind.UNKNOWN,
    /** 图集张数（列表页常能拿到） */
    val mediaCount: Int? = null,
    /** 视频时长（秒） */
    val durationSec: Int? = null,
    val publishedAt: Long? = null,
    /** 正文摘要（列表页通常只有一小段） */
    val excerpt: String? = null,
    val likedCount: Int? = null
)

/**
 * 博主主页解析结果。
 *
 * 与单篇 [ParseResult] 平行：主页是"一个作者 + N 篇笔记"的集合形态，
 * 媒体资源不在这一层展开（否则一次要抓 N 个页面），点开单篇时再解析。
 */
@Serializable
data class ProfileResult(
    val platform: Platform,
    /** 命中的解析器 ID，便于排查 */
    val resolverId: String,
    val sourceUrl: String,
    val userId: String? = null,
    val nickname: String? = null,
    val avatar: String? = null,
    val bio: String? = null,
    val stats: ProfileStats? = null,
    val posts: List<ProfilePost> = emptyList(),
    /** 是否还有下一页（用于"加载更多"） */
    val hasMore: Boolean = false,
    /** 下一页游标（各平台语义不同，原样回传即可） */
    val nextCursor: String? = null,
    /** 业务级提示（如需登录态只能拿到部分内容） */
    val warning: String? = null,
    val parsedAtMillis: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean get() = posts.isEmpty()

    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: "未知博主"
}

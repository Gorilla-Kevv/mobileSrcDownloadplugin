package com.clipdown.app.data

import android.content.Context
import com.clipdown.parser.model.Platform
import com.clipdown.parser.parsers.IgRiskGuard

/**
 * 平台登录态仓库。
 *
 * 需要登录才能拿到原画质资源的平台（Instagram / 小红书 / Facebook 等），
 * 由用户在设置页粘贴 Cookie 或通过内置浏览器一键登录捕获，
 * 解析内核在请求时通过 cookieProvider 回调取用。
 *
 * 存储用独立 SP，且只在本地保存，绝不随任何网络请求外发到第三方。
 */
object CookieStore {

    private const val PREF = "clipdown_cookies"

    fun get(context: Context, platform: Platform): String? {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(platform.id, null)?.takeIf { it.isNotBlank() } ?: return null
        // 自动防御：清理历史留下的无核心鉴权会话令牌的非登录态伪 Cookie（如仅包含 mid/csrftoken），
        // 避免误判为已登录而发起私有请求撞风控
        if (!isValidSessionCookie(platform, raw)) {
            remove(context, platform)
            return null
        }
        return raw
    }

    fun put(context: Context, platform: Platform, cookie: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(platform.id, cookie.trim())
            .apply()
        if (platform == Platform.INSTAGRAM) {
            IgRiskGuard.reset()
        }
    }

    fun remove(context: Context, platform: Platform) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove(platform.id)
            .apply()
        if (platform == Platform.INSTAGRAM) {
            IgRiskGuard.reset()
        }
    }

    fun has(context: Context, platform: Platform): Boolean = !get(context, platform).isNullOrBlank()

    /** 校验 Cookie 是否真正包含核心鉴权会话令牌，杜绝以游客匿名 Cookie 充数 */
    fun isValidSessionCookie(platform: Platform, cookie: String): Boolean = when (platform) {
        Platform.INSTAGRAM -> cookie.contains("sessionid=")
        Platform.XIAOHONGSHU -> cookie.contains("web_session=") || cookie.contains("a1=")
        Platform.FACEBOOK -> cookie.contains("c_user=") || cookie.contains("xs=")
        else -> cookie.isNotBlank() && cookie.length >= 20
    }

    /**
     * 提供"补充 Cookie"入口的平台。
     *
     * 注意与 [Platform.loginRequired] 解耦：小红书笔记解析已实测免登录可用
     * （2026-10-08 真机，标注为公开可解析），但其**主页解析仍强依赖 Cookie**，
     * 所以设置页的 Cookie 入口保留。
     */
    fun platformsNeedingCookie(): List<Platform> =
        listOf(Platform.INSTAGRAM, Platform.XIAOHONGSHU, Platform.FACEBOOK)
}

package com.clipdown.app.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// 色彩令牌
//
// 命名规则：<语义><色阶>。页面只允许引用令牌，不再写裸 Color(0xFF...)，
// 保证同一语义在不同页面永远是同一个颜色（这是"排版整齐"的底层前提）。
// ─────────────────────────────────────────────────────────────────────────────

// 品牌色：靛蓝。600 为主行动色，容器色用于选中态底、标签底
val Brand600 = Color(0xFF3D5AFE)
val Brand700 = Color(0xFF2A41D6)
val Brand500 = Color(0xFF6178FF)
val BrandContainer = Color(0xFFE9EDFF)
val OnBrandContainer = Color(0xFF23308F)

// 中性色阶（浅色主题）
val NeutralBg = Color(0xFFF4F6FB)          // 页面底
val NeutralSurface = Color(0xFFFFFFFF)     // 卡片底
val NeutralSurfaceAlt = Color(0xFFF7F8FC)  // 次级块底（输入框、缩略图占位）
val NeutralOutline = Color(0xFFE4E7F0)     // 1dp 描边（卡片分界）
val NeutralOutlineStrong = Color(0xFFD2D7E5)
val TextPrimary = Color(0xFF141824)
val TextSecondary = Color(0xFF5A6072)
val TextTertiary = Color(0xFF8A90A2)

// 中性色阶（深色主题）
val DarkBg = Color(0xFF0E1220)
val DarkSurface = Color(0xFF171C2E)
val DarkSurfaceAlt = Color(0xFF1F2537)
val DarkOutline = Color(0xFF2A3145)
val DarkTextPrimary = Color(0xFFEDEFF6)
val DarkTextSecondary = Color(0xFFA6ADC0)
val DarkTextTertiary = Color(0xFF7B839A)

// 语义色（含容器色，用于 pill / 提示块）
val SuccessFg = Color(0xFF15803D)
val SuccessContainer = Color(0xFFE6F6EC)
val WarningFg = Color(0xFFB45309)
val WarningContainer = Color(0xFFFDF3E3)
val DangerFg = Color(0xFFB42318)
val DangerContainer = Color(0xFFFDECEA)
val InfoFg = Color(0xFF1D4ED8)
val InfoContainer = Color(0xFFE8EEFF)

// ── 兼容旧引用的别名（悬浮窗等历史代码仍在使用） ─────────────────────────────
/** 主色：靛蓝，用于强调按钮与选中态 */
val SeedBlue = Brand600
val OnSeedBlue = Color(0xFFFFFFFF)

/** 毛玻璃基调色：低饱和度深蓝灰，配合高透明度形成磨砂玻璃观感 */
val GlassBase = Color(0xFF101426)
val GlassBaseLight = Color(0xFFE8ECFA)

val SuccessGreen = SuccessFg
val WarningAmber = WarningFg
val DangerRed = DangerFg

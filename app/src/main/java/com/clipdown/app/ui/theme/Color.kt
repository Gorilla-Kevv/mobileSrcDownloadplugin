package com.clipdown.app.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
// 色彩令牌：【神秘 · 紫色 · 黑暗 · 隐私】（Obsidian Aegis / 秘境黑紫·隐秘私域）
//
// 命名规则：<语义><色阶>。页面只允许引用令牌，保证视觉语言的高度统一与纯粹沉浸。
// ─────────────────────────────────────────────────────────────────────────────

// 品牌核心色：神秘霓虹幻紫 / 秘境紫晶（Mystic Violet & Neon Amethyst）
val Brand600 = Color(0xFF9333EA)
val Brand700 = Color(0xFF7E22CE)
val Brand500 = Color(0xFFC084FC)
val BrandPrimary = Color(0xFFA855F7)
val BrandContainer = Color(0xFF26123D)
val OnBrandContainer = Color(0xFFF3E8FF)
val BrandGlow = Color(0x66A855F7)

// 暗黑隐私色阶（主主题：Obsidian Vault 极夜玄黑与黑曜紫晶）
val DarkBg = Color(0xFF09060E)             // 极夜玄黑（OLED 深度暗黑底色）
val DarkSurface = Color(0xFF130E1F)        // 黑曜紫晶卡片底色
val DarkSurfaceAlt = Color(0xFF1C142B)     // 次级交互块底（输入框、占位块、展开区）
val DarkOutline = Color(0xFF2E2048)        // 发丝微光描边（幽静卡片分界）
val DarkOutlineStrong = Color(0xFF4C3575)  // 高亮状态描边
val DarkTextPrimary = Color(0xFFF5F3FF)    // 星芒亮白（极高对比度，WCAG AAA）
val DarkTextSecondary = Color(0xFFB8AEC8)  // 雾霭淡紫灰（舒适次要文本）
val DarkTextTertiary = Color(0xFF786E8A)   // 深渊静默灰（辅助标注）

// 浅色兼容色阶（淡雅紫薰衣草调）
val NeutralBg = Color(0xFFF7F5FC)
val NeutralSurface = Color(0xFFFFFFFF)
val NeutralSurfaceAlt = Color(0xFFF1ECFA)
val NeutralOutline = Color(0xFFE5DCF5)
val NeutralOutlineStrong = Color(0xFFD0C2EB)
val TextPrimary = Color(0xFF1E1333)
val TextSecondary = Color(0xFF63567D)
val TextTertiary = Color(0xFF9485AF)

// 语义色调（专为暗黑紫调精调，避免传统高纯度刺眼饱和度）
val SuccessFg = Color(0xFF34D399)          // 幽光翡翠绿
val SuccessContainer = Color(0xFF102820)
val WarningFg = Color(0xFFFBBF24)          // 暗夜琥珀金
val WarningContainer = Color(0xFF2C1F0A)
val DangerFg = Color(0xFFF87171)           // 绯红炽焰
val DangerContainer = Color(0xFF2E0F14)
val InfoFg = Color(0xFF818CF8)             // 星芒电光蓝紫
val InfoContainer = Color(0xFF171A38)

// ── 兼容旧引用的别名 ─────────────────────────────────────────────────────────
val SeedBlue = BrandPrimary
val OnSeedBlue = Color(0xFFFFFFFF)

/** 毛玻璃基调色：磨砂深紫夜底，带紫光透射感 */
val GlassBase = Color(0xFF0D0818)
val GlassBaseLight = Color(0xFFF1ECFA)
val GlassBorder = Color(0x33A855F7)

val SuccessGreen = SuccessFg
val WarningAmber = WarningFg
val DangerRed = DangerFg

package com.clipdown.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// 间距 / 圆角令牌
// ─────────────────────────────────────────────────────────────────────────────
data class Spacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    /** 页面左右安全边距 */
    val screen: Dp = 16.dp
)

data class Radius(
    /** 卡片 */
    val card: Dp = 18.dp,
    /** 按钮 / 输入框等控件 */
    val control: Dp = 12.dp,
    /** 缩略图 */
    val thumb: Dp = 14.dp,
    /** 胶囊标签 */
    val pill: Dp = 999.dp
)

private val LocalSpacing = staticCompositionLocalOf { Spacing() }
private val LocalRadius = staticCompositionLocalOf { Radius() }

/** 页面内取令牌：`AppTheme.spacing.lg` / `AppTheme.radius.card` */
object AppTheme {
    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current

    val radius: Radius
        @Composable @ReadOnlyComposable get() = LocalRadius.current
}

// ─────────────────────────────────────────────────────────────────────────────
// 排版：收敛为 10 个层级、3 个字重，行高统一
// ─────────────────────────────────────────────────────────────────────────────
private val AppTypography = Typography(
    // 页面主标题
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    // 卡片标题
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    // 正文
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    // 辅助信息
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

private val LightColors = lightColorScheme(
    primary = Brand600,
    onPrimary = Color.White,
    primaryContainer = NeutralSurfaceAlt,
    onPrimaryContainer = Brand700,
    secondary = BrandPrimary,
    onSecondary = Color.White,
    secondaryContainer = NeutralSurfaceAlt,
    onSecondaryContainer = Brand700,
    tertiary = InfoFg,
    onTertiary = Color.White,
    background = NeutralBg,
    onBackground = TextPrimary,
    surface = NeutralSurface,
    onSurface = TextPrimary,
    surfaceVariant = NeutralSurfaceAlt,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHighest = NeutralSurfaceAlt,
    outline = NeutralOutlineStrong,
    outlineVariant = NeutralOutline,
    error = DangerFg,
    onError = Color.White,
    errorContainer = DangerContainer,
    onErrorContainer = DangerFg
)

private val DarkColors = darkColorScheme(
    primary = BrandPrimary,
    onPrimary = Color(0xFF09060E),
    primaryContainer = BrandContainer,
    onPrimaryContainer = OnBrandContainer,
    secondary = Brand500,
    onSecondary = Color(0xFF09060E),
    secondaryContainer = BrandContainer,
    onSecondaryContainer = OnBrandContainer,
    tertiary = InfoFg,
    onTertiary = Color(0xFF09060E),
    background = DarkBg,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceAlt,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainerHighest = DarkSurfaceAlt,
    outline = DarkOutlineStrong,
    outlineVariant = DarkOutline,
    error = DangerFg,
    onError = Color(0xFF09060E),
    errorContainer = DangerContainer,
    onErrorContainer = DangerFg
)

/**
 * 应用主题：【神秘 · 紫色 · 黑暗 · 隐私】
 *
 * 默认**全沉浸暗黑秘夜优先**（darkTheme = true）：
 * 告别平庸蓝灰，全面沉浸至极夜玄黑底色与黑曜紫晶卡片层级，
 * 无论应用界面还是悬浮窗磨砂玻璃，均呈现浑然一体的高级隐私与神秘科技感。
 */
@Composable
fun ClipDownTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalRadius provides Radius()
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}

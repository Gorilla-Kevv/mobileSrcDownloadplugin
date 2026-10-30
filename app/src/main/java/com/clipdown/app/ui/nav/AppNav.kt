package com.clipdown.app.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clipdown.app.ui.downloads.DownloadsScreen
import com.clipdown.app.ui.home.HomeScreen
import com.clipdown.app.ui.profile.ProfileScreen
import com.clipdown.app.ui.settings.SettingsScreen
import com.clipdown.app.ui.theme.Brand500
import com.clipdown.app.ui.theme.Brand600
import com.clipdown.app.ui.theme.BrandPrimary
import kotlinx.coroutines.launch

sealed class Route(val path: String, val label: String, val icon: ImageVector) {
    data object Home : Route("home", "首页", Icons.Default.Home)
    data object Profile : Route("profile", "主页解析", Icons.Default.Person)
    data object Downloads : Route("downloads", "下载", Icons.Default.Download)
    data object Settings : Route("settings", "设置", Icons.Default.Settings)
}

private val routes = listOf(Route.Home, Route.Profile, Route.Downloads, Route.Settings)

/**
 * 应用主导航容器：全面支持左右全屏手势流畅滑动切换页面与高质感底部导航栏联动交互。
 *
 * 核心交互特性：
 * 1. 【双向手势联动】：左右滑动切换页面时，底部导航栏顶部的紫晶指示条严格随手指 1:1 在 RenderThread 平滑移动；
 * 2. 【灵敏目标预判】：底栏选项的选中态随手势越过中线时（targetPage）即刻弹性激活，配合微弹簧缩放；
 * 3. 【无缝状态常驻】：beyondViewportPageCount 保证 4 个核心页面全部常驻内存，左右滑动零重绘、零卡顿、无白屏；
 * 4. 【系统返回键兜底】：在非首页标签页点击系统物理返回键时，优先平滑滑动返回首页，防止误退应用；
 * 5. 【细腻触觉反馈】：滑动手势过界与点击标签页均触发细微触觉震动反馈。
 */
@Composable
fun AppNav(openParse: Boolean, openProfile: Boolean = false) {
    val pagerState = rememberPagerState(initialPage = 0) { routes.size }
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val scrollToPage: (Int) -> Unit = { pageIndex ->
        coroutineScope.launch {
            pagerState.animateScrollToPage(
                page = pageIndex,
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
            )
        }
    }

    // 外部指令跳转主页标签页（例如来自气泡快捷入口或 Intent）
    LaunchedEffect(openProfile) {
        if (openProfile) {
            scrollToPage(1)
        }
    }

    // 系统返回键交互设计：非首页时点击返回平滑滑向首页，而非直接退出应用
    BackHandler(enabled = pagerState.currentPage != 0) {
        scrollToPage(0)
    }

    // 页面滑动切换目标变化时的轻微触觉反馈
    LaunchedEffect(pagerState.targetPage) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    Scaffold(
        bottomBar = {
            Column {
                // 顶部平滑联动指示条轨道：在微光发丝线上渲染随手指滑动 1:1 精确滑动的荧光紫晶指示胶囊
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                ) {
                    val totalWidthPx = constraints.maxWidth.toFloat()
                    val tabWidthPx = totalWidthPx / routes.size
                    val indicatorWidthDp = 38.dp

                    Box(
                        modifier = Modifier
                            .width(indicatorWidthDp)
                            .height(2.5.dp)
                            .graphicsLayer {
                                val progress = pagerState.currentPage + pagerState.currentPageOffsetFraction
                                val centerPx = (progress + 0.5f) * tabWidthPx
                                translationX = centerPx - (size.width / 2f)
                            }
                            .clip(RoundedCornerShape(1.25.dp))
                            .background(
                                brush = Brush.horizontalGradient(
                                    listOf(Brand500, BrandPrimary, Brand600)
                                )
                            )
                    )
                }

                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    val targetPage = pagerState.targetPage
                    routes.forEachIndexed { index, route ->
                        val isSelected = targetPage == index
                        val iconScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.15f else 1.0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "navIconScale"
                        )

                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (pagerState.currentPage != index) {
                                    scrollToPage(index)
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = route.icon,
                                    contentDescription = route.label,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                        }
                                )
                            },
                            label = {
                                Text(
                                    text = route.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 3,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { page ->
            when (page) {
                0 -> HomeScreen(
                    autoFocusParse = openParse,
                    onOpenProfile = { scrollToPage(1) },
                    onOpenDownloads = { scrollToPage(2) }
                )
                1 -> ProfileScreen()
                2 -> DownloadsScreen()
                3 -> SettingsScreen()
            }
        }
    }
}

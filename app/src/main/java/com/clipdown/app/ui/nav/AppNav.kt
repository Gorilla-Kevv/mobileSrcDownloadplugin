package com.clipdown.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.clipdown.app.ui.downloads.DownloadsScreen
import com.clipdown.app.ui.home.HomeScreen
import com.clipdown.app.ui.profile.ProfileScreen
import com.clipdown.app.ui.settings.SettingsScreen

sealed class Route(val path: String, val label: String, val icon: ImageVector) {
    data object Home : Route("home", "首页", Icons.Default.Home)
    data object Profile : Route("profile", "主页解析", Icons.Default.Person)
    data object Downloads : Route("downloads", "下载", Icons.Default.Download)
    data object Settings : Route("settings", "设置", Icons.Default.Settings)
}

private val routes = listOf(Route.Home, Route.Profile, Route.Downloads, Route.Settings)

@Composable
fun AppNav(openParse: Boolean, openProfile: Boolean = false) {
    val navController = rememberNavController()

    // 与底部导航一致的跳转方式：避免把标签页重复压栈，并保留各标签页状态
    val goToProfile: () -> Unit = {
        navController.navigate(Route.Profile.path) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // 从首页/气泡入口打开主页标签页
    LaunchedEffect(openProfile) {
        if (openProfile) goToProfile()
    }

    Scaffold(
        bottomBar = {
            // 底部导航：白底 + 顶部 1dp 发丝线，避免默认阴影造成脏边
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    val entry by navController.currentBackStackEntryAsState()
                    val current = entry?.destination
                    routes.forEach { route ->
                        NavigationBarItem(
                            selected = current?.hierarchy?.any { it.route == route.path } == true,
                            onClick = {
                                navController.navigate(route.path) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(route.icon, contentDescription = route.label, modifier = Modifier.size(22.dp)) },
                            label = { Text(route.label, style = MaterialTheme.typography.labelMedium) },
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
        NavHost(
            navController = navController,
            startDestination = Route.Home.path,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            composable(Route.Home.path) {
                HomeScreen(
                    autoFocusParse = openParse,
                    onOpenProfile = goToProfile
                )
            }
            composable(Route.Downloads.path) { DownloadsScreen() }
            composable(Route.Settings.path) { SettingsScreen() }
            composable(Route.Profile.path) { ProfileScreen() }
        }
    }
}

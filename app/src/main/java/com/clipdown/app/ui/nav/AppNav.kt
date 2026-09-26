package com.clipdown.app.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.clipdown.app.ui.downloads.DownloadsScreen
import com.clipdown.app.ui.home.HomeScreen
import com.clipdown.app.ui.settings.SettingsScreen
import com.clipdown.app.ui.theme.SeedBlue

sealed class Route(val path: String, val label: String, val icon: ImageVector) {
    data object Home : Route("home", "首页", Icons.Default.Home)
    data object Downloads : Route("downloads", "下载", Icons.Default.Download)
    data object Settings : Route("settings", "设置", Icons.Default.Settings)
}

private val routes = listOf(Route.Home, Route.Downloads, Route.Settings)

@Composable
fun AppNav(openParse: Boolean) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
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
                        icon = { Icon(route.icon, contentDescription = route.label) },
                        label = { Text(route.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = SeedBlue,
                            selectedTextColor = SeedBlue,
                            indicatorColor = SeedBlue.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Route.Home.path,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            composable(Route.Home.path) { HomeScreen(autoFocusParse = openParse) }
            composable(Route.Downloads.path) { DownloadsScreen() }
            composable(Route.Settings.path) { SettingsScreen() }
        }
    }
}

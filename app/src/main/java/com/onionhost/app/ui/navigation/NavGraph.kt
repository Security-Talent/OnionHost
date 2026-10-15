package com.onionhost.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.onionhost.app.chat.ChatInviteLinkStore
import com.onionhost.app.ui.about.AboutScreen
import com.onionhost.app.ui.analytics.AnalyticsScreen
import com.onionhost.app.ui.analytics.AnalyticsViewModel
import com.onionhost.app.ui.chat.AnonymousChatScreen
import com.onionhost.app.ui.home.HomeScreen
import com.onionhost.app.ui.home.HomeViewModel
import com.onionhost.app.ui.logs.LogsScreen
import com.onionhost.app.ui.logs.LogsViewModel
import com.onionhost.app.ui.settings.SettingsScreen
import com.onionhost.app.ui.settings.SettingsViewModel
import com.onionhost.app.ui.websites.WebsitesScreen
import com.onionhost.app.ui.websites.WebsitesViewModel

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Chat : Screen("chat", "Chats", Icons.Default.Chat)
    object Websites : Screen("websites", "Websites", Icons.Default.Web)
    object More : Screen("more", "More", Icons.Default.Menu)
    object Analytics : Screen("analytics", "Analytics", Icons.Default.BarChart)
    object Logs : Screen("logs", "Logs", Icons.Default.Terminal)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object About : Screen("about", "About", Icons.Default.Info)
}

val navItems = listOf(
    Screen.Home,
    Screen.Chat,
    Screen.Websites,
    Screen.More
)

private val moreRoutes = setOf(Screen.Analytics.route, Screen.Logs.route, Screen.Settings.route, Screen.About.route)

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val incomingChatLink by ChatInviteLinkStore.pendingLink.collectAsState()
    LaunchedEffect(incomingChatLink, currentRoute) {
        if (!incomingChatLink.isNullOrBlank() && currentRoute != Screen.Chat.route) {
            navController.navigate(Screen.Chat.route) { launchSingleTop = true }
        }
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                navItems.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = {
                            Text(
                                if (screen == Screen.Websites) "Sites" else screen.title,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip
                            )
                        },
                        alwaysShowLabel = true,
                        selected = if (screen == Screen.More) currentRoute == Screen.More.route || currentRoute in moreRoutes
                            else currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                val vm: HomeViewModel = hiltViewModel()
                HomeScreen(viewModel = vm)
            }
            composable(Screen.Chat.route) {
                val vm: HomeViewModel = hiltViewModel()
                val context = LocalContext.current
                LaunchedEffect(incomingChatLink) {
                    incomingChatLink?.let { link ->
                        vm.joinChatInvite(context, link)
                        ChatInviteLinkStore.consume(link)
                    }
                }
                AnonymousChatScreen(viewModel = vm)
            }
            composable(Screen.Websites.route) {
                val vm: WebsitesViewModel = hiltViewModel()
                WebsitesScreen(viewModel = vm)
            }
            composable(Screen.More.route) {
                MoreScreen { destination ->
                    navController.navigate(destination) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
            composable(Screen.Analytics.route) {
                val vm: AnalyticsViewModel = hiltViewModel()
                AnalyticsScreen(viewModel = vm)
            }
            composable(Screen.Logs.route) {
                val vm: LogsViewModel = hiltViewModel()
                LogsScreen(viewModel = vm)
            }
            composable(Screen.Settings.route) {
                val vm: SettingsViewModel = hiltViewModel()
                SettingsScreen(viewModel = vm)
            }
            composable(Screen.About.route) {
                AboutScreen()
            }
        }
    }

}

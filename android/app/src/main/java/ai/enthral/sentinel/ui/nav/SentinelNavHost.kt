package ai.enthral.sentinel.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ai.enthral.sentinel.R
import ai.enthral.sentinel.ui.home.HomeScreen
import ai.enthral.sentinel.ui.placeholder.PlaceholderScreen

@Composable
fun SentinelNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { dest ->
                    val label = stringResource(dest.label)
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == dest.route } == true,
                        onClick = {
                            nav.navigate(dest.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Destination.Home.route, modifier = Modifier.padding(padding)) {
            composable(Destination.Home.route) { HomeScreen() }
            composable(Destination.Alerts.route) {
                PlaceholderScreen(R.string.nav_alerts, R.string.placeholder_alerts_body)
            }
            composable(Destination.Check.route) {
                PlaceholderScreen(R.string.nav_check, R.string.placeholder_check_body)
            }
            composable(Destination.Family.route) {
                PlaceholderScreen(R.string.nav_family, R.string.placeholder_family_body)
            }
            composable(Destination.Settings.route) {
                PlaceholderScreen(R.string.nav_settings, R.string.placeholder_settings_body)
            }
        }
    }
}

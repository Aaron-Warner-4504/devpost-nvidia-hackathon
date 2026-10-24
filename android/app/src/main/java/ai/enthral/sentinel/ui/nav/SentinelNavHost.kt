package ai.enthral.sentinel.ui.nav

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer
import ai.enthral.sentinel.ui.common.findActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import ai.enthral.sentinel.AppGraph
import ai.enthral.sentinel.LocalDemoEnabled
import ai.enthral.sentinel.R
import ai.enthral.sentinel.ui.alerts.AlertDetailScreen
import ai.enthral.sentinel.ui.alerts.AlertsScreen
import ai.enthral.sentinel.ui.check.CheckScreen
import ai.enthral.sentinel.ui.common.LocalAppGraph
import ai.enthral.sentinel.ui.demo.DemoScreen
import ai.enthral.sentinel.ui.family.FamilyScreen
import ai.enthral.sentinel.ui.home.HomeScreen
import ai.enthral.sentinel.ui.onboarding.OnboardingScreen
import ai.enthral.sentinel.ui.settings.SettingsScreen
import kotlin.reflect.KClass

private class Tab(val route: Any, val type: KClass<*>, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Home, Home::class, R.string.nav_home, Icons.Filled.Home),
    Tab(Alerts, Alerts::class, R.string.nav_alerts, Icons.Filled.Notifications),
    Tab(Check, Check::class, R.string.nav_check, Icons.Filled.Check),
    Tab(Family, Family::class, R.string.nav_family, Icons.Filled.Person),
    Tab(Settings, Settings::class, R.string.nav_settings, Icons.Filled.Settings),
)

/** Switch tabs keeping each tab's own back stack and scroll state. */
private fun NavController.navigateTab(route: Any) = navigate(route) {
    popUpTo<Home> { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SentinelRoot(graph: AppGraph, startOnboarded: Boolean) {
    CompositionLocalProvider(LocalAppGraph provides graph, LocalDemoEnabled provides graph.demoEnabled) {
        val nav = rememberNavController()
        val done by graph.settings.onboardingDone.collectAsStateWithLifecycle(initialValue = startOnboarded)
        var shown by rememberSaveable { mutableStateOf(startOnboarded) }
        // Finishing onboarding / deleting all data flips this flag: reset the whole back stack accordingly.
        LaunchedEffect(done) {
            if (done != shown) {
                shown = done
                nav.navigate(if (done) Home else Onboarding) { popUpTo(nav.graph.id) { inclusive = true } }
            }
        }
        // singleTop: tapping a notification while the app is open arrives in onNewIntent, which NavHost does not see.
        val activity = LocalContext.current.findActivity() as? ComponentActivity
        val doneNow by rememberUpdatedState(done)
        DisposableEffect(nav, activity) {
            val listener = Consumer<Intent> { intent -> if (doneNow) nav.handleDeepLink(intent) }
            activity?.addOnNewIntentListener(listener)
            onDispose { activity?.removeOnNewIntentListener(listener) }
        }
        val share by graph.shareIntake.pending.collectAsStateWithLifecycle()
        LaunchedEffect(share != null, done) {
            if (share != null && done) nav.navigateTab(Check)
        }

        val backStack by nav.currentBackStackEntryAsState()
        val destination = backStack?.destination
        val onTab = tabs.any { t -> destination?.hierarchy?.any { it.hasRoute(t.type) } == true }
        val imeVisible = WindowInsets.isImeVisible

        Scaffold(
            modifier = Modifier.imePadding(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (onTab && !imeVisible) {
                    // Icons + one-word labels cannot grow without being cut off, so cap the bar's text at 1.3x
                    // (everything else scales fully). Each item keeps its full name for TalkBack.
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, 1.3f)),
                    ) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = destination?.hierarchy?.any { it.hasRoute(tab.type) } == true,
                                onClick = { nav.navigateTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                    }
                }
            },
        ) { padding ->
            NavHost(
                nav,
                startDestination = if (startOnboarded) Home else Onboarding,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            ) {
                composable<Onboarding> { OnboardingScreen() }
                composable<Home> {
                    HomeScreen(
                        onOpenAlert = { nav.navigate(AlertDetail(it)) },
                        onOpenSettings = { nav.navigateTab(Settings) },
                        onOpenCheck = { nav.navigateTab(Check) },
                        onOpenDemo = { nav.navigate(Demo) },
                    )
                }
                composable<Alerts> { AlertsScreen(onOpenAlert = { nav.navigate(AlertDetail(it)) }) }
                composable<Check> { CheckScreen(onOpenResult = { nav.navigate(AlertDetail(it)) }) }
                composable<Family> { FamilyScreen() }
                composable<Settings> { SettingsScreen(onOpenDemo = { nav.navigate(Demo) }) }
                composable<AlertDetail>(
                    deepLinks = listOf(navDeepLink<AlertDetail>(basePath = "sentinel://alert")),
                ) { entry ->
                    AlertDetailScreen(entry.toRoute<AlertDetail>().id, onBack = { nav.popBackStack() })
                }
                composable<Demo> {
                    DemoScreen(onBack = { nav.popBackStack() }, onOpenAlert = { nav.navigate(AlertDetail(it)) })
                }
            }
        }
    }
}

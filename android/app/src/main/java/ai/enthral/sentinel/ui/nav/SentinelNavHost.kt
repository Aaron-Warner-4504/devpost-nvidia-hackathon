package ai.enthral.sentinel.ui.nav

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import ai.enthral.sentinel.ui.common.LocalPrivacyRequest
import ai.enthral.sentinel.ui.common.LocalWidthClass
import ai.enthral.sentinel.ui.components.FloatingBarHeight
import ai.enthral.sentinel.ui.components.FloatingBarMargin
import ai.enthral.sentinel.ui.components.FloatingTabBar
import ai.enthral.sentinel.ui.components.LocalBottomBarInset
import ai.enthral.sentinel.ui.components.LocalHaze
import ai.enthral.sentinel.ui.components.NavItem
import ai.enthral.sentinel.ui.components.NavRail
import ai.enthral.sentinel.ui.components.NavSidebar
import ai.enthral.sentinel.ui.components.WidthClass
import ai.enthral.sentinel.ui.components.AtmosphereBox
import ai.enthral.sentinel.ui.components.widthClassOf
import ai.enthral.sentinel.ui.theme.LocalGlass
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
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
import ai.enthral.sentinel.ui.common.findActivity
import ai.enthral.sentinel.ui.common.rememberReducedMotion
import ai.enthral.sentinel.ui.components.SentinelIcons
import ai.enthral.sentinel.ui.demo.DemoScreen
import ai.enthral.sentinel.ui.family.FamilyScreen
import ai.enthral.sentinel.ui.home.HomeScreen
import ai.enthral.sentinel.ui.onboarding.OnboardingScreen
import ai.enthral.sentinel.ui.settings.SettingsScreen
import kotlin.reflect.KClass

private class Tab(val route: Any, val type: KClass<*>, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Home, Home::class, R.string.nav_home, SentinelIcons.Home, SentinelIcons.HomeSelected),
    Tab(Alerts, Alerts::class, R.string.nav_alerts, SentinelIcons.Alerts, SentinelIcons.AlertsSelected),
    Tab(Check, Check::class, R.string.nav_check, SentinelIcons.Check, SentinelIcons.CheckSelected),
    Tab(Family, Family::class, R.string.nav_family, SentinelIcons.Family, SentinelIcons.FamilySelected),
    Tab(Settings, Settings::class, R.string.nav_settings, SentinelIcons.Settings, SentinelIcons.SettingsSelected),
)

private fun NavBackStackEntry.isTab() = tabs.any { t -> destination.hierarchy.any { it.hasRoute(t.type) } }

/** Switch tabs keeping each tab's own back stack and scroll state. */
private fun NavController.navigateTab(route: Any) = navigate(route) {
    popUpTo<Home> { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private val pushSpring = spring<IntOffset>(stiffness = 500f)

/** Tab <-> tab: fade-through. Push/pop: shared axis X. Nothing when animations are off. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(reduced: Boolean, pop: Boolean): EnterTransition = when {
    reduced -> EnterTransition.None
    initialState.isTab() && targetState.isTab() -> fadeIn(tween(210, delayMillis = 60)) + scaleIn(tween(240), initialScale = 0.985f)
    else -> slideInHorizontally(pushSpring) { w -> if (pop) -w / 12 else w / 8 } + fadeIn(tween(200))
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(reduced: Boolean, pop: Boolean): ExitTransition = when {
    reduced -> ExitTransition.None
    initialState.isTab() && targetState.isTab() -> fadeOut(tween(90))
    else -> slideOutHorizontally(pushSpring) { w -> if (pop) w / 8 else -w / 12 } + fadeOut(tween(160))
}

@OptIn(ExperimentalLayoutApi::class)
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
        val selected = tabs.indexOfFirst { t -> destination?.hierarchy?.any { it.hasRoute(t.type) } == true }
        val onboarding = destination?.hierarchy?.any { it.hasRoute(Onboarding::class) } == true || (destination == null && !startOnboarded)
        val imeVisible = WindowInsets.isImeVisible
        val reduced = rememberReducedMotion()
        val items = tabs.map { NavItem(stringResource(it.label), it.icon, it.selectedIcon) }
        val glass = LocalGlass.current
        val haze = if (glass.blur) rememberHazeState() else null
        val privacyRequest = remember { mutableStateOf(false) }

        // One NavHost instance, moved between the compact and rail/sidebar layouts so window resizes keep state.
        val host = remember(nav) {
            movableContentOf { modifier: Modifier ->
                NavHost(
                    nav,
                    startDestination = if (startOnboarded) Home else Onboarding,
                    modifier = modifier,
                    enterTransition = { enter(reduced, pop = false) },
                    exitTransition = { exit(reduced, pop = false) },
                    popEnterTransition = { enter(reduced, pop = true) },
                    popExitTransition = { exit(reduced, pop = true) },
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

        CompositionLocalProvider(LocalHaze provides haze, LocalPrivacyRequest provides privacyRequest) {
            AtmosphereBox { BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
                val widthClass = widthClassOf(maxWidth)
                CompositionLocalProvider(LocalWidthClass provides widthClass) {
                    if (widthClass == WidthClass.Compact) {
                        // Phones: floating glass bar in thumb reach; content scrolls beneath it.
                        val showBar = selected >= 0 && !imeVisible
                        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        val inset = if (showBar) FloatingBarHeight + FloatingBarMargin + navInset else 0.dp
                        CompositionLocalProvider(LocalBottomBarInset provides inset) {
                            Box(Modifier.fillMaxSize()) {
                                host(Modifier.fillMaxSize())
                                if (showBar) {
                                    FloatingTabBar(items, selected, { nav.navigateTab(tabs[it].route) }, Modifier.align(Alignment.BottomCenter))
                                }
                            }
                        }
                    } else {
                        // Tablets, foldables, landscape: rail (medium) or labelled sidebar (expanded) at the leading edge.
                        Row(Modifier.fillMaxSize()) {
                            if (!onboarding) {
                                if (widthClass == WidthClass.Medium) {
                                    NavRail(items, selected, { nav.navigateTab(tabs[it].route) })
                                } else {
                                    val demoEnabled = LocalDemoEnabled.current
                                    val secondary = buildList {
                                        if (demoEnabled) add(NavItem(stringResource(R.string.demo_title), SentinelIcons.Sparkles) to { nav.navigate(Demo) })
                                        add(NavItem(stringResource(R.string.settings_privacy), SentinelIcons.Lock) to {
                                            nav.navigateTab(Settings)
                                            privacyRequest.value = true
                                        })
                                    }
                                    NavSidebar(
                                        appName = stringResource(R.string.app_name),
                                        items = items,
                                        selected = selected,
                                        onSelect = { nav.navigateTab(tabs[it].route) },
                                        secondaryTitle = stringResource(R.string.nav_more),
                                        secondary = secondary,
                                        footer = "${stringResource(R.string.about_version)} ${ai.enthral.sentinel.BuildConfig.VERSION_NAME}",
                                    )
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxHeight()) { host(Modifier.fillMaxSize()) }
                        }
                    }
                }
            } }
        }
    }
}

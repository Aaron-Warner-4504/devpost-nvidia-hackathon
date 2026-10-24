package ai.enthral.sentinel.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import ai.enthral.sentinel.R

/** Top-level destinations shown in the bottom bar. To add a screen: add an entry, then a `composable` in SentinelNavHost. */
enum class Destination(val route: String, @StringRes val label: Int, val icon: ImageVector) {
    Home("home", R.string.nav_home, Icons.Filled.Home),
    Alerts("alerts", R.string.nav_alerts, Icons.Filled.Notifications),
    Check("check", R.string.nav_check, Icons.Filled.Check),
    Family("family", R.string.nav_family, Icons.Filled.Person),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
}

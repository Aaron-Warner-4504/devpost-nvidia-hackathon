package ai.enthral.sentinel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import ai.enthral.sentinel.ui.nav.SentinelNavHost
import ai.enthral.sentinel.ui.theme.SentinelTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // transparent system bars; icon contrast follows the system theme
        val container = (application as SentinelApp).container
        setContent {
            val dynamic by container.settings.useDynamicColor.collectAsState(initial = false)
            SentinelTheme(darkTheme = isSystemInDarkTheme(), dynamicColor = dynamic) {
                SentinelNavHost()
            }
        }
    }
}

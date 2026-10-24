package ai.enthral.sentinel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import ai.enthral.sentinel.domain.SharedContent
import ai.enthral.sentinel.ui.nav.SentinelRoot
import ai.enthral.sentinel.ui.theme.SentinelTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val graph: AppGraph get() = (application as SentinelApp).container
    private var startOnboarded: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // transparent system bars; icon contrast follows the system theme
        splash.setKeepOnScreenCondition { startOnboarded == null }
        graph.notifier.ensureChannels()
        if (savedInstanceState == null) handleShare(intent)
        lifecycleScope.launch {
            // Hold the splash until we know which screen to start on (a few ms from DataStore).
            startOnboarded = graph.settings.onboardingDone.first()
            setContent {
                val trueBlack by graph.settings.useTrueBlack.collectAsState(initial = false)
                SentinelTheme(darkTheme = isSystemInDarkTheme(), trueBlack = trueBlack) {
                    SentinelRoot(graph, startOnboarded == true)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** ACTION_SEND with text or an image: hand it to the Check screen, which pre-fills from it. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        when {
            type.startsWith("image/") -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) graph.shareIntake.offer(SharedContent(text = null, imageUri = uri.toString()))
            }
            type == "text/plain" -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (!text.isNullOrBlank()) graph.shareIntake.offer(SharedContent(text = text, imageUri = null))
            }
        }
    }
}

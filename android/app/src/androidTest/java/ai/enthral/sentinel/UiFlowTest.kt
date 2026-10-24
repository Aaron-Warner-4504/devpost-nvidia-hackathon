package ai.enthral.sentinel

import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.testing.FakeGraph
import ai.enthral.sentinel.testing.Samples
import ai.enthral.sentinel.testing.httpError
import ai.enthral.sentinel.ui.alerts.AlertDetailBody
import ai.enthral.sentinel.ui.check.CheckScreen
import ai.enthral.sentinel.ui.common.LocalAppGraph
import ai.enthral.sentinel.ui.nav.SentinelRoot
import ai.enthral.sentinel.ui.theme.SentinelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compose UI tests against fake repositories (English strings; run on the emulator via connectedDebugAndroidTest). */
class UiFlowTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @org.junit.Before fun edgeToEdge() {
        // Like MainActivity: the app is edge-to-edge, so insets (and the keyboard) are reported the way users see them.
        compose.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }

    @Test fun onboarding_happy_path_reaches_home() {
        val graph = FakeGraph()
        compose.setContent { SentinelTheme(darkTheme = false) { SentinelRoot(graph, startOnboarded = false) } }

        compose.onNodeWithText("Welcome to Sentinel").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Our three promises").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Choose your language").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("What may Sentinel watch?").assertIsDisplayed()

        // Nothing registered yet: registration is gated on finishing consent.
        assertEquals(0, graph.session.registerCalls)
        compose.onNodeWithText("Finish setup").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("Quick actions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Setup incomplete").assertIsDisplayed()
        assertEquals(1, graph.session.registerCalls)
        assertTrue(graph.settings.done.value)
        // Home is the real screen now: bottom navigation is present
        compose.onNodeWithText("Alerts").assertIsDisplayed()
    }

    @Test fun alert_detail_shows_why_timeline_and_feedback() {
        val picked = mutableListOf<FeedbackLabel>()
        val alert = Samples.alert("alr_1", Tier.Critical)
        compose.setContent {
            SentinelTheme(darkTheme = false) {
                AlertDetailBody(alert, reaction = null, now = Samples.now, onFeedback = { picked += it })
            }
        }
        compose.onNodeWithText("Critical").assertIsDisplayed()
        compose.onNodeWithText("Why we flagged this").assertIsDisplayed()
        compose.onNodeWithText("Courier SMS with a link", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Unknown call, 4 minutes", substring = true).assertIsDisplayed()
        compose.onNodeWithText("This message").assertIsDisplayed() // current event highlighted
        compose.onNodeWithText("Do not pay. Call your bank.").assertIsDisplayed()
        compose.onNodeWithText("Pressure to act fast").assertIsDisplayed() // signal in plain language
        compose.onNodeWithText("This was a scam").performScrollTo().performClick()
        assertEquals(listOf(FeedbackLabel.Scam), picked)
    }

    @Test fun check_error_keeps_the_typed_text() {
        val graph = FakeGraph().also { it.checks.result = httpError(429, "rate limit exceeded") }
        var hostView: android.view.View? = null
        compose.setContent {
            hostView = androidx.compose.ui.platform.LocalView.current
            SentinelTheme(darkTheme = false) {
                CompositionLocalProvider(LocalAppGraph provides graph) { CheckScreen(onOpenResult = {}) }
            }
        }
        compose.onNode(hasSetTextAction() and androidx.compose.ui.test.hasText("Message to check")).performTextInput("Pay now or be arrested")
        compose.onNodeWithText("Check").performClick()

        compose.waitUntil(5_000) { compose.onAllNodesWithText("The check did not finish").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("You have made a lot of checks recently", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Pay now or be arrested", substring = true).assertIsDisplayed()
        compose.onNodeWithText("What you entered is still here.").assertIsDisplayed()
        assertEquals("Pay now or be arrested", graph.checks.texts.single().first)
        // Do not leave the software keyboard up for the next test (it would hide the bottom bar).
        compose.runOnUiThread {
            val v = hostView!!
            androidx.core.view.ViewCompat.getWindowInsetsController(v)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
    }
}

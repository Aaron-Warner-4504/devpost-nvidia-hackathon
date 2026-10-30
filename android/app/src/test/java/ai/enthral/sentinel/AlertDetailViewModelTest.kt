package ai.enthral.sentinel

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.testing.FakeAlerts
import ai.enthral.sentinel.testing.FakeSettings
import ai.enthral.sentinel.testing.Samples
import ai.enthral.sentinel.testing.httpError
import ai.enthral.sentinel.ui.alerts.AlertDetailViewModel
import ai.enthral.sentinel.ui.alerts.DetailEvent
import ai.enthral.sentinel.ui.alerts.DetailState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlertDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule(kotlinx.coroutines.test.StandardTestDispatcher())

    private val alert = Samples.alert("alr_1")
    private val repo = FakeAlerts(listOf(alert))
    private val settings = FakeSettings()

    private fun kotlinx.coroutines.test.TestScope.make(): AlertDetailViewModel {
        val vm = AlertDetailViewModel("alr_1", repo, settings, this, undoWindowMs = 5_000)
        runCurrent()
        return vm
    }

    @Test fun `loads the alert and the remembered reaction`() = runTest {
        settings.reactionMap.value = mapOf("alr_1" to FeedbackLabel.Safe)
        val vm = make()
        val s = vm.state.value as DetailState.Content
        assertEquals(alert, s.alert)
        assertEquals(FeedbackLabel.Safe, s.reaction)
    }

    @Test fun `opening the detail loads the latest server version, not the stale cached one`() = runTest {
        // the server refined the headline after the list was cached
        repo.refreshResult = ApiResult.Success(listOf(alert.copy(headline = "Refined headline")))
        val vm = make()
        val s = vm.state.value as DetailState.Content
        assertEquals("Refined headline", s.alert.headline)
        assertEquals(1, repo.refreshCalls)
    }

    @Test fun `a failed refresh keeps the cached alert on screen`() = runTest {
        repo.refreshResult = httpError(500)
        val vm = make()
        assertEquals(alert, (vm.state.value as DetailState.Content).alert)
    }

    @Test fun `unknown alert id ends in NotFound`() = runTest {
        val vm = AlertDetailViewModel("nope", repo, settings, this)
        runCurrent()
        assertEquals(DetailState.NotFound, vm.state.value)
    }

    @Test fun `feedback is optimistic and only sent after the undo window`() = runTest {
        val vm = make()
        val events = mutableListOf<DetailEvent>()
        backgroundScope.launch { vm.events.flow.collect { events += it } }
        vm.onFeedback(FeedbackLabel.Scam)
        runCurrent()
        assertEquals(FeedbackLabel.Scam, (vm.state.value as DetailState.Content).reaction)
        assertEquals(listOf<DetailEvent>(DetailEvent.ShowUndo(FeedbackLabel.Scam)), events)
        assertTrue("not sent yet", repo.feedback.isEmpty())
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(listOf("alr_1" to FeedbackLabel.Scam), repo.feedback)
    }

    @Test fun `undo restores the previous state and never calls the API`() = runTest {
        settings.reactionMap.value = mapOf("alr_1" to FeedbackLabel.Safe)
        val vm = make()
        vm.onFeedback(FeedbackLabel.Scam)
        runCurrent()
        vm.undo()
        runCurrent()
        assertEquals(FeedbackLabel.Safe, (vm.state.value as DetailState.Content).reaction)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(repo.feedback.isEmpty())
    }

    @Test fun `undo with no previous verdict clears it`() = runTest {
        val vm = make()
        vm.onFeedback(FeedbackLabel.Safe)
        runCurrent()
        vm.undo()
        runCurrent()
        assertNull((vm.state.value as DetailState.Content).reaction)
    }

    @Test fun `a second tap inside the window replaces the first and sends once`() = runTest {
        val vm = make()
        vm.onFeedback(FeedbackLabel.Scam)
        runCurrent()
        advanceTimeBy(2_000)
        vm.onFeedback(FeedbackLabel.Safe)
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(listOf("alr_1" to FeedbackLabel.Safe), repo.feedback)
    }

    @Test fun `a failed send rolls the reaction back and says so`() = runTest {
        repo.feedbackResult = httpError(500)
        val vm = make()
        val events = mutableListOf<DetailEvent>()
        backgroundScope.launch { vm.events.flow.collect { events += it } }
        vm.onFeedback(FeedbackLabel.Scam)
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertNull((vm.state.value as DetailState.Content).reaction)
        assertTrue(events.contains(DetailEvent.FeedbackFailed))
    }

    @Test fun `leaving the screen flushes a pending verdict instead of losing it`() = runTest {
        val store = ViewModelStore()
        val vm = ViewModelProvider.create(
            store,
            viewModelFactory { initializer { AlertDetailViewModel("alr_1", repo, settings, this@runTest, 5_000) } },
        )[AlertDetailViewModel::class.java]
        runCurrent()
        vm.onFeedback(FeedbackLabel.Scam)
        runCurrent()
        assertTrue(repo.feedback.isEmpty())
        store.clear() // back navigation
        runCurrent()
        assertEquals(listOf("alr_1" to FeedbackLabel.Scam), repo.feedback)
    }
}

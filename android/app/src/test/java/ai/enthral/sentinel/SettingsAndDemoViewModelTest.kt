package ai.enthral.sentinel

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.demo.DemoKey
import ai.enthral.sentinel.data.demo.DemoOutcome
import ai.enthral.sentinel.data.demo.DemoStep
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.testing.FakeAccess
import ai.enthral.sentinel.testing.FakeAccount
import ai.enthral.sentinel.testing.FakeConsent
import ai.enthral.sentinel.testing.FakeDemo
import ai.enthral.sentinel.testing.FakeLocale
import ai.enthral.sentinel.testing.FakeSettings
import ai.enthral.sentinel.testing.NetworkDown
import ai.enthral.sentinel.testing.Samples
import ai.enthral.sentinel.ui.demo.DemoEvent
import ai.enthral.sentinel.ui.demo.DemoPhase
import ai.enthral.sentinel.ui.demo.DemoViewModel
import ai.enthral.sentinel.ui.settings.SettingsViewModel
import ai.enthral.sentinel.ui.settings.isDeleteConfirmed
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val settings = FakeSettings().apply { done.value = true }
    private val consent = FakeConsent()
    private val access = FakeAccess(notification = true)
    private val account = FakeAccount(settings)
    private fun vm() = SettingsViewModel(settings, access, consent, account, FakeLocale(), null, demoEnabled = true)

    @Test fun `delete needs the confirm word, any case`() {
        assertTrue(isDeleteConfirmed("DELETE"))
        assertTrue(isDeleteConfirmed(" delete "))
        assertFalse(isDeleteConfirmed("del"))
        assertFalse(isDeleteConfirmed(""))
    }

    @Test fun `delete everything wipes local state and leaves onboarding for the root to show`() = runTest {
        val vm = vm()
        vm.setPaused(true)
        vm.deleteEverything()
        assertEquals(1, account.calls)
        assertEquals(1, settings.cleared)
        assertFalse("root navigates back to onboarding when this flips", settings.done.value)
        assertFalse(vm.state.value.deleting)
        assertNull(vm.state.value.deleteFailure)
    }

    @Test fun `a failed delete keeps everything and can be retried`() = runTest {
        account.result = NetworkDown
        val vm = vm()
        vm.deleteEverything()
        assertNotNull(vm.state.value.deleteFailure)
        assertTrue(settings.done.value)
        assertEquals(0, settings.cleared)
        account.result = ApiResult.Success(Unit)
        vm.deleteEverything()
        assertNull(vm.state.value.deleteFailure)
        assertFalse(settings.done.value)
    }

    @Test fun `toggling a source off tells the server straight away`() = runTest {
        val vm = vm()
        vm.setSource(ConsentSource.Notifications, true)
        vm.setSource(ConsentSource.Notifications, false)
        assertEquals(ConsentSource.Notifications to false, consent.calls.last())
        assertFalse(vm.state.value.sources.effective(ConsentSource.Notifications))
    }

    @Test fun `a failed consent sync is reported and retried on resume`() = runTest {
        consent.result = NetworkDown
        val vm = vm()
        vm.setSource(ConsentSource.Notifications, true)
        assertTrue(vm.state.value.consentSyncFailed)
        consent.result = ApiResult.Success(Unit)
        val before = consent.calls.size
        vm.onResume()
        assertTrue(consent.calls.size > before)
        assertFalse(vm.state.value.consentSyncFailed)
    }

    @Test fun `revoking a permission in system settings is noticed on resume`() = runTest {
        val vm = vm()
        vm.setSource(ConsentSource.Notifications, true)
        consent.calls.clear()
        access.notification = false
        vm.onResume()
        assertTrue(consent.calls.contains(ConsentSource.Notifications to false))
    }
}

class DemoViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val critical = Samples.alert("alr_demo", Tier.Critical)
    private val caution = Samples.alert("alr_c", Tier.Caution)
    private fun steps(vararg o: DemoOutcome) = DemoKey.entries.zip(o.toList()).map { (k, out) -> DemoStep(k, out) }
    private val success = listOf(
        steps(DemoOutcome.Pending, DemoOutcome.Pending, DemoOutcome.Pending),
        steps(DemoOutcome.Flagged(caution), DemoOutcome.NoAlert, DemoOutcome.Flagged(critical)),
    )

    @Test fun `runs the stepper and ends on the critical alert`() = runTest {
        val vm = DemoViewModel(FakeDemo(success))
        val events = collectEvents(vm.events.flow)
        vm.start()
        assertEquals(DemoPhase.Done, vm.state.value.phase)
        assertEquals("alr_demo", vm.state.value.finalAlertId)
        assertEquals(listOf<DemoEvent>(DemoEvent.OpenAlert("alr_demo")), events)
    }

    @Test fun `running twice is fine and yields the same ending`() = runTest {
        val demo = FakeDemo(success)
        val vm = DemoViewModel(demo)
        vm.start()
        vm.start()
        assertEquals(2, demo.calls)
        assertEquals("alr_demo", vm.state.value.finalAlertId)
    }

    @Test fun `a failing step stops the story and offers retry`() = runTest {
        val failed = DemoOutcome.Failed(NetworkDown)
        val vm = DemoViewModel(FakeDemo(listOf(steps(DemoOutcome.Flagged(caution), failed, DemoOutcome.Pending))))
        val events = collectEvents(vm.events.flow)
        vm.start()
        assertEquals(DemoPhase.Failed, vm.state.value.phase)
        assertTrue("does not jump to an alert after a failure", events.isEmpty())
    }
}

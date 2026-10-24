package ai.enthral.sentinel

import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.testing.FakeAccess
import ai.enthral.sentinel.testing.FakeConsent
import ai.enthral.sentinel.testing.FakeLocale
import ai.enthral.sentinel.testing.FakeSession
import ai.enthral.sentinel.testing.FakeSettings
import ai.enthral.sentinel.testing.NetworkDown
import ai.enthral.sentinel.ui.onboarding.OnboardingViewModel
import ai.enthral.sentinel.ui.onboarding.SystemRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val settings = FakeSettings()
    private val session = FakeSession()
    private val consent = FakeConsent()
    private val access = FakeAccess()
    private val locale = FakeLocale()
    private fun vm() = OnboardingViewModel(session, consent, settings, access, locale)

    @Test fun `nothing talks to the server before the user finishes`() = runTest {
        val vm = vm()
        vm.setSource(ConsentSource.Notifications, true)
        vm.setSource(ConsentSource.Calls, true)
        vm.setSource(ConsentSource.Calls, false)
        assertEquals("no registration before Finish", 0, session.registerCalls)
        assertTrue("no consent calls before Finish", consent.calls.isEmpty())
        assertFalse(settings.done.value)
    }

    @Test fun `finish registers first then records every source, then marks onboarding done`() = runTest {
        access.notification = true // granted in system settings
        val vm = vm()
        vm.setSource(ConsentSource.Notifications, true)
        vm.setSource(ConsentSource.Calls, true) // intent on, but READ_CALL_LOG not granted
        vm.finish()
        assertEquals(1, session.registerCalls)
        assertEquals(
            mapOf(ConsentSource.Notifications to true, ConsentSource.Calls to false, ConsentSource.Check to true),
            consent.calls.toMap(),
        )
        assertTrue(settings.done.value)
        assertNull(vm.state.value.failure)
        assertFalse(vm.state.value.finishing)
    }

    @Test fun `registration failure shows an error, keeps onboarding open and can be retried`() = runTest {
        session.registerResult = NetworkDown
        val vm = vm()
        vm.finish()
        assertNotNull(vm.state.value.failure)
        assertFalse(settings.done.value)
        assertTrue(consent.calls.isEmpty())

        session.registerResult = ai.enthral.sentinel.data.api.ApiResult.Success(Unit)
        vm.finish()
        assertNull(vm.state.value.failure)
        assertTrue(settings.done.value)
    }

    @Test fun `a failing consent call also surfaces as retryable`() = runTest {
        consent.result = NetworkDown
        val vm = vm()
        vm.finish()
        assertNotNull(vm.state.value.failure)
        assertFalse(settings.done.value)
    }

    @Test fun `turning notifications on asks for system notification access`() = runTest {
        val vm = vm()
        val seen = collectEvents(vm.requests.flow)
        vm.setSource(ConsentSource.Notifications, true)
        assertEquals(listOf<SystemRequest>(SystemRequest.OpenNotificationAccess), seen)
    }

    @Test fun `already granted access needs no system screen`() = runTest {
        access.notification = true
        val vm = vm()
        val seen = collectEvents(vm.requests.flow)
        vm.setSource(ConsentSource.Notifications, true)
        assertTrue(seen.isEmpty())
    }

    @Test fun `calls ask for the runtime permission, then send you to settings once it is blocked`() = runTest {
        val vm = vm()
        val seen = collectEvents(vm.requests.flow)
        vm.setSource(ConsentSource.Calls, true)
        vm.onCallPermissionResult(granted = false, permanentlyDenied = true)
        vm.fixSource(ConsentSource.Calls)
        assertEquals(listOf(SystemRequest.RequestCallPermission, SystemRequest.OpenAppSettings), seen)
    }

    @Test fun `resume re-reads what the OS really granted`() = runTest {
        val vm = vm()
        vm.setSource(ConsentSource.Notifications, true)
        assertEquals(ai.enthral.sentinel.domain.SourceStatus.NeedsPermission, vm.state.value.sources.notifications)
        access.notification = true // user flipped it in system settings and came back
        vm.onResume()
        assertEquals(ai.enthral.sentinel.domain.SourceStatus.On, vm.state.value.sources.notifications)
    }

    @Test fun `language choice is applied through the locale controller`() = runTest {
        val vm = vm()
        vm.setLanguage("hi")
        assertEquals("hi", locale.tag)
        assertEquals("hi", vm.language())
    }
}

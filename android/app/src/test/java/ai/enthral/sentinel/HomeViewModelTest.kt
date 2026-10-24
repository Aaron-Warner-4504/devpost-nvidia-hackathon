package ai.enthral.sentinel

import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.ProtectionStatus
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.testing.FakeAccess
import ai.enthral.sentinel.testing.FakeAlerts
import ai.enthral.sentinel.testing.FakeSettings
import ai.enthral.sentinel.testing.NetworkDown
import ai.enthral.sentinel.testing.Samples
import ai.enthral.sentinel.ui.home.HomeViewModel
import ai.enthral.sentinel.ui.home.Phase
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val settings = FakeSettings()
    private val access = FakeAccess()
    private fun vm(repo: FakeAlerts) = HomeViewModel(repo, settings, access, { Samples.now }, ZoneOffset.UTC)

    @Test fun `no source on means setup incomplete`() = runTest {
        val vm = vm(FakeAlerts())
        assertEquals(ProtectionStatus.SetupIncomplete, vm.state.value.protection)
        assertEquals(Phase.Ready, vm.state.value.phase)
    }

    @Test fun `granted source means protected, and pausing flips the hero`() = runTest {
        access.notification = true
        settings.setConsentIntent(ConsentSource.Notifications, true)
        val vm = vm(FakeAlerts())
        assertEquals(ProtectionStatus.Protected, vm.state.value.protection)
        settings.setMonitoringPaused(true)
        assertEquals(ProtectionStatus.Paused, vm.state.value.protection)
    }

    @Test fun `summary counts today's alerts by tier and shows the latest`() = runTest {
        val a = Samples.alert("a", Tier.Critical, Samples.now.minusSeconds(60))
        val b = Samples.alert("b", Tier.Caution, Samples.now.minusSeconds(3600))
        val old = Samples.alert("c", Tier.High, Samples.now.minusSeconds(3 * 86400))
        val vm = vm(FakeAlerts(listOf(b, a, old)))
        assertEquals(mapOf(Tier.Critical to 1, Tier.Caution to 1), vm.state.value.todayCounts)
        assertEquals("a", vm.state.value.latest?.id)
    }

    @Test fun `offline with a cache keeps content and flags offline, without a cache it is an error`() = runTest {
        val cached = FakeAlerts(listOf(Samples.alert()))
        cached.refreshResult = NetworkDown
        val v1 = vm(cached)
        assertEquals(Phase.Ready, v1.state.value.phase)
        assertEquals(true, v1.state.value.offline)

        val empty = FakeAlerts().apply { refreshResult = NetworkDown }
        val v2 = vm(empty)
        assertEquals(Phase.Error, v2.state.value.phase)
        assertNotNull(v2.state.value.failure)
    }

    @Test fun `offline turns a protected hero into the offline hero`() = runTest {
        access.notification = true
        settings.setConsentIntent(ConsentSource.Notifications, true)
        val repo = FakeAlerts(listOf(Samples.alert())).apply { refreshResult = NetworkDown }
        assertEquals(ProtectionStatus.Offline, vm(repo).state.value.protection)
    }
}

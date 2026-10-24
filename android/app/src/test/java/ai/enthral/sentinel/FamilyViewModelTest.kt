package ai.enthral.sentinel

import ai.enthral.sentinel.domain.FamilyLinks
import ai.enthral.sentinel.domain.Guardian
import ai.enthral.sentinel.testing.FakeFamily
import ai.enthral.sentinel.testing.FakeFcm
import ai.enthral.sentinel.testing.NetworkDown
import ai.enthral.sentinel.testing.Samples
import ai.enthral.sentinel.testing.httpError
import ai.enthral.sentinel.ui.family.FamilyError
import ai.enthral.sentinel.ui.family.FamilyViewModel
import ai.enthral.sentinel.ui.family.LinksState
import ai.enthral.sentinel.ui.family.formatCountdown
import ai.enthral.sentinel.ui.family.normalizeInviteCode
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FamilyViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val family = FakeFamily()
    private var now: Instant = Samples.now
    private fun vm() = FamilyViewModel(family, FakeFcm()) { now }

    @Test fun `code input is normalised to the invite alphabet and 6 characters`() {
        assertEquals("K7M2QX", normalizeInviteCode("k7m2qx"))
        assertEquals("K7M2QX", normalizeInviteCode(" k7m-2qx \n"))   // pasted with spaces/dashes
        assertEquals("K7M2QX", normalizeInviteCode("K7M2QXZZZ"))
        assertEquals("K7M", normalizeInviteCode("K7M0O1IL"))        // 0, O, 1, I, L never occur in codes
        assertEquals("", normalizeInviteCode("!!!"))
    }

    @Test fun `accept needs a full code and a name`() = runTest {
        val vm = vm()
        vm.onCodeChange("k7m2q")
        vm.onNameChange("Ravi")
        assertFalse(vm.state.value.canAccept)
        vm.onCodeChange("k7m2qx")
        assertTrue(vm.state.value.canAccept)
        vm.onNameChange("   ")
        assertFalse(vm.state.value.canAccept)
    }

    @Test fun `accept sends upper-case code, trimmed name and the push token, then reloads links`() = runTest {
        val vm = vm()
        vm.onCodeChange("k7m2qx"); vm.onNameChange("  Ravi ")
        family.links = ai.enthral.sentinel.data.api.ApiResult.Success(
            FamilyLinks(emptyList(), listOf(ai.enthral.sentinel.domain.Protectee("fam_9", Samples.now))),
        )
        vm.accept()
        assertEquals(Triple("K7M2QX", "Ravi", "fcm-test-token"), family.accepted.single())
        assertTrue(vm.state.value.accepted)
        assertEquals("", vm.state.value.code)
        assertEquals(1, ((vm.state.value.links) as LinksState.Ready).links.protecting.size)
    }

    @Test fun `accept errors are friendly and keep what was typed`() = runTest {
        val cases = mapOf(
            httpError(404) to FamilyError.InvalidCode,
            httpError(429) to FamilyError.RateLimited,
            httpError(409) to FamilyError.GuardianLimit,
            httpError(422) to FamilyError.InvalidInput,
            NetworkDown to FamilyError.Offline,
        )
        for ((failure, expected) in cases) {
            family.accept = failure
            val vm = vm()
            vm.onCodeChange("k7m2qx"); vm.onNameChange("Ravi")
            vm.accept()
            assertEquals(expected, vm.state.value.acceptError)
            assertEquals("K7M2QX", vm.state.value.code)
            assertFalse(vm.state.value.accepted)
        }
    }

    @Test fun `no invite is created without the explicit consent switch`() = runTest {
        val vm = vm()
        vm.createInvite()
        assertEquals(0, family.inviteCalls)
        vm.setConsent(true)
        vm.createInvite()
        assertEquals(1, family.inviteCalls)
        assertEquals("K7M2QX", vm.state.value.invite?.code)
    }

    @Test fun `countdown follows the clock and expires after 10 minutes`() = runTest {
        val vm = vm()
        vm.setConsent(true)
        vm.createInvite()
        assertEquals(600, vm.state.value.remainingSeconds)
        now = now.plusSeconds(61)
        vm.tick()
        assertEquals(539, vm.state.value.remainingSeconds)
        assertFalse(vm.state.value.inviteExpired)
        now = now.plusSeconds(10_000)
        vm.tick()
        assertEquals(0, vm.state.value.remainingSeconds)
        assertTrue(vm.state.value.inviteExpired)
    }

    @Test fun `countdown formats as mm ss`() {
        assertEquals("10:00", formatCountdown(600))
        assertEquals("00:07", formatCountdown(7))
    }

    @Test fun `invite errors such as the hourly limit are mapped`() = runTest {
        family.invite = httpError(429)
        val vm = vm()
        vm.setConsent(true)
        vm.createInvite()
        assertEquals(FamilyError.RateLimited, vm.state.value.inviteError)
        assertEquals(null, vm.state.value.invite)
    }

    @Test fun `links load, unlink calls the API and reloads`() = runTest {
        family.links = ai.enthral.sentinel.data.api.ApiResult.Success(
            FamilyLinks(listOf(Guardian("fam_1", "Ravi", Samples.now)), emptyList()),
        )
        val vm = vm()
        assertEquals("Ravi", (vm.state.value.links as LinksState.Ready).links.guardians.single().name)
        family.links = ai.enthral.sentinel.data.api.ApiResult.Success(FamilyLinks(emptyList(), emptyList()))
        vm.unlink("fam_1")
        assertEquals(listOf("fam_1"), family.unlinked)
        assertTrue((vm.state.value.links as LinksState.Ready).links.guardians.isEmpty())
    }

    @Test fun `a failed links load can be retried`() = runTest {
        family.links = NetworkDown
        val vm = vm()
        assertEquals(LinksState.Failed, vm.state.value.links)
        family.links = ai.enthral.sentinel.data.api.ApiResult.Success(FamilyLinks(emptyList(), emptyList()))
        vm.loadLinks()
        assertTrue(vm.state.value.links is LinksState.Ready)
    }
}

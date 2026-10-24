package ai.enthral.sentinel

import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.testing.FakeAlerts
import ai.enthral.sentinel.testing.NetworkDown
import ai.enthral.sentinel.testing.Samples
import ai.enthral.sentinel.ui.alerts.AlertFilter
import ai.enthral.sentinel.ui.alerts.AlertsViewModel
import ai.enthral.sentinel.ui.common.DayBucket
import ai.enthral.sentinel.ui.common.bucketFor
import ai.enthral.sentinel.ui.common.countsToday
import ai.enthral.sentinel.ui.common.groupByDay
import ai.enthral.sentinel.ui.home.Phase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AlertsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val utc: ZoneId = ZoneOffset.UTC
    private val now = Samples.now // 2026-10-24T12:00Z

    private fun at(id: String, tier: Tier, hoursAgo: Long) =
        Samples.alert(id, tier, createdAt = now.minusSeconds(hoursAgo * 3600))

    private val sample = listOf(
        at("a", Tier.Critical, 1),
        at("b", Tier.High, 2),
        at("c", Tier.Caution, 20),   // yesterday 16:00
        at("d", Tier.High, 24 * 5),  // five days ago
    )

    @Test fun `groups are today, yesterday and dated, newest first`() {
        val groups = groupByDay(sample, now, utc)
        assertEquals(
            listOf(DayBucket.Today, DayBucket.Yesterday, DayBucket.On(LocalDate.of(2026, 10, 19))),
            groups.map { it.bucket },
        )
        assertEquals(listOf("a", "b"), groups[0].alerts.map { it.id })
    }

    @Test fun `day boundaries use the local zone`() {
        val ist = ZoneId.of("Asia/Kolkata")
        val lateUtc = Instant.parse("2026-10-23T20:00:00Z") // 01:30 on the 24th in India
        assertEquals(DayBucket.Today, bucketFor(lateUtc, now, ist))
        assertEquals(DayBucket.Yesterday, bucketFor(lateUtc, now, utc))
    }

    @Test fun `counts today by tier`() {
        assertEquals(mapOf(Tier.Critical to 1, Tier.High to 1), countsToday(sample, now, utc))
    }

    @Test fun `tier filter narrows the list and All restores it`() = runTest {
        val vm = AlertsViewModel(FakeAlerts(sample), { now }, utc)
        assertEquals(4, vm.state.value.groups.sumOf { it.alerts.size })
        vm.setFilter(AlertFilter.High)
        assertEquals(listOf("b", "d"), vm.state.value.groups.flatMap { it.alerts }.map { it.id })
        vm.setFilter(AlertFilter.Caution)
        assertEquals(listOf("c"), vm.state.value.groups.flatMap { it.alerts }.map { it.id })
        vm.setFilter(AlertFilter.All)
        assertEquals(4, vm.state.value.groups.sumOf { it.alerts.size })
    }

    @Test fun `a filter that hides everything says so instead of looking empty`() = runTest {
        val vm = AlertsViewModel(FakeAlerts(listOf(at("a", Tier.High, 1))), { now }, utc)
        vm.setFilter(AlertFilter.Critical)
        assertTrue(vm.state.value.filteredOut)
        assertTrue(vm.state.value.groups.isEmpty())
    }

    @Test fun `loads on start and reports a network failure`() = runTest {
        val repo = FakeAlerts().apply { refreshResult = NetworkDown }
        val vm = AlertsViewModel(repo, { now }, utc)
        assertEquals(Phase.Error, vm.state.value.phase)
        repo.refreshResult = null
        repo.set(sample)
        vm.refresh()
        assertEquals(Phase.Ready, vm.state.value.phase)
        assertFalse(vm.state.value.refreshing)
    }

    @Test fun `cached alerts stay visible when a refresh fails`() = runTest {
        val repo = FakeAlerts(sample)
        val vm = AlertsViewModel(repo, { now }, utc)
        repo.refreshResult = NetworkDown
        vm.refresh()
        assertEquals(Phase.Ready, vm.state.value.phase)
        assertEquals(4, vm.state.value.groups.sumOf { it.alerts.size })
    }
}

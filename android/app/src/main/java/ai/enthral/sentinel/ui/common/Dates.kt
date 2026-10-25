package ai.enthral.sentinel.ui.common

import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.Tier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface DayBucket {
    data object Today : DayBucket
    data object Yesterday : DayBucket
    data class On(val date: LocalDate) : DayBucket
}

data class AlertGroup(val bucket: DayBucket, val alerts: List<Alert>)

fun bucketFor(instant: Instant, now: Instant, zone: ZoneId): DayBucket {
    val day = instant.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    return when (day) {
        today -> DayBucket.Today
        today.minusDays(1) -> DayBucket.Yesterday
        else -> DayBucket.On(day)
    }
}

/** Groups newest-first alerts by local calendar day, keeping order. */
fun groupByDay(alerts: List<Alert>, now: Instant, zone: ZoneId): List<AlertGroup> =
    alerts.sortedByDescending { it.createdAt }
        .groupBy { bucketFor(it.createdAt, now, zone) }
        .map { (bucket, list) -> AlertGroup(bucket, list) }

/** Today's most serious alert worth the user's attention (critical before high; newest first). Null if none. */
fun attentionToday(alerts: List<Alert>, now: Instant, zone: ZoneId): Alert? =
    alerts.filter { (it.tier == Tier.Critical || it.tier == Tier.High) && bucketFor(it.createdAt, now, zone) == DayBucket.Today }
        .sortedWith(compareByDescending<Alert> { it.tier.ordinal }.thenByDescending { it.createdAt })
        .firstOrNull()

fun countsToday(alerts: List<Alert>, now: Instant, zone: ZoneId): Map<Tier, Int> =
    alerts.filter { bucketFor(it.createdAt, now, zone) == DayBucket.Today }
        .groupingBy { it.tier }.eachCount()

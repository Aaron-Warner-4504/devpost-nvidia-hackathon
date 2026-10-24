package ai.enthral.sentinel.ui.common

import java.time.Duration
import java.time.Instant

sealed interface RelativeTime {
    data object JustNow : RelativeTime
    data class MinutesAgo(val n: Int) : RelativeTime
    data class HoursAgo(val n: Int) : RelativeTime
    data class DaysAgo(val n: Int) : RelativeTime
}

/** Coarse "x ago" bucket, formatted with plurals in the UI (system DateUtils would ignore the per-app language). */
fun relativeTime(then: Instant, now: Instant): RelativeTime {
    val d = Duration.between(then, now)
    return when {
        d.isNegative || d.toMinutes() < 1 -> RelativeTime.JustNow
        d.toMinutes() < 60 -> RelativeTime.MinutesAgo(d.toMinutes().toInt())
        d.toHours() < 24 -> RelativeTime.HoursAgo(d.toHours().toInt())
        else -> RelativeTime.DaysAgo(d.toDays().toInt())
    }
}

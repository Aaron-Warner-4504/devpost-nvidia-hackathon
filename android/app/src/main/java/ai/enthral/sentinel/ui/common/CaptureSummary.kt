package ai.enthral.sentinel.ui.common

import ai.enthral.sentinel.capture.CaptureStatus
import ai.enthral.sentinel.capture.StatsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** What notification capture is really doing right now. */
enum class CaptureState {
    /** Consent given, notification access granted, not paused. */
    Active,
    Paused,

    /** Consent given but Android's Notification access is off. */
    NeedsAccess,

    /** The user has not turned the Notifications source on. */
    Off,
}

fun captureState(sources: SourcesState, paused: Boolean): CaptureState = when {
    !sources.notificationsIntent -> CaptureState.Off
    !sources.notificationAccess -> CaptureState.NeedsAccess
    paused -> CaptureState.Paused
    else -> CaptureState.Active
}

/** Numbers for the Home card and Settings. Never content. */
data class CaptureNumbers(
    val watchedApps: Int = 0,
    val queued: Int = 0,
    val stats: StatsSnapshot = StatsSnapshot(),
)

fun CaptureStatus?.numbers(): Flow<CaptureNumbers> {
    if (this == null) return flowOf(CaptureNumbers())
    return combine(stats, queueSize) { s, q -> CaptureNumbers(installedWatchedApps().size, q, s) }
}

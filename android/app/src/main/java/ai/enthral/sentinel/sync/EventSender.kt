package ai.enthral.sentinel.sync

import ai.enthral.sentinel.capture.CaptureStats
import ai.enthral.sentinel.capture.Counter
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.api.toDomain
import ai.enthral.sentinel.data.notify.AlertNotifier
import ai.enthral.sentinel.data.repo.DeviceSession
import ai.enthral.sentinel.data.store.AppSettings
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.Tier
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Background jobs the capture path asks for. WorkManager in the app, a recorder in tests. */
interface SyncScheduler {
    /** Drain the outbox when the network is available (unique, exponential backoff). */
    fun scheduleSync(delayMs: Long = 0)

    /** One-time GET /v1/alerts checks for late background-review upgrades. */
    fun scheduleFollowUps(delaysMs: List<Long>)

    /** Read new call-log rows (after a call ends). */
    fun scheduleCallLogSync(delayMs: Long) = Unit
}

/**
 * Surfaces alerts at most once per id: high/critical -> heads-up notification, caution -> silent (Alerts tab).
 * Shares the notified-id set with AlertsRepository so neither path repeats the other's notification.
 */
class AlertDispatcher(private val settings: AppSettings, private val notifier: AlertNotifier) {
    private val lock = Mutex()

    /** Returns the alerts that were newly notified. */
    suspend fun dispatch(alerts: List<Alert>): List<Alert> = lock.withLock {
        if (alerts.isEmpty()) return emptyList()
        val seen = settings.notifiedAlertIds.first().orEmpty()
        val fresh = alerts.filter { it.id !in seen }.distinctBy { it.id }
        if (fresh.isEmpty()) return emptyList()
        val loud = fresh.filter { it.tier.interrupts }.sortedBy { it.createdAt }
        loud.forEach { notifier.notify(it) }
        settings.markNotified(fresh.map { it.id }.toSet())
        loud
    }
}

/** Late-upgrade follow-up plan: after a send, look again at +10 s, +30 s and +90 s. */
object FollowUpPolicy {
    val DELAYS_MS = listOf(10_000L, 30_000L, 90_000L)

    /** Follow up when a response had tier >= caution, or when anything was sent in the last minute. */
    fun shouldFollowUp(maxTier: Tier?, lastSentAt: Long?, now: Long): Boolean =
        (maxTier != null && maxTier >= Tier.Caution) || (lastSentAt != null && now - lastSentAt <= 60_000L)
}

enum class DrainResult {
    /** Queue empty (or only items that were dropped). */
    Done,

    /** Network / 5xx / auth trouble: keep the items and retry with backoff. */
    Retry,

    /** 429: back off before trying again. */
    Backoff,

    /** Onboarding not finished: nothing may be sent yet. */
    NotReady,
}

/**
 * Drains the outbox oldest-first. One drain at a time (mutex), so the fast path and the worker never double-post;
 * even if they did, the stable device_event_id makes the server answer `duplicate: true`.
 *  - 2xx: remove, count, surface the alert;
 *  - 401: DeviceSession re-registers once and retries; still 401 -> Retry later;
 *  - 422 / 413 / other 4xx: the item can never succeed -> drop and count;
 *  - 429: stop, Backoff;  - 5xx, 408, network, timeout: stop, Retry.
 */
class EventSender(
    private val api: SentinelApi,
    private val session: DeviceSession,
    private val settings: AppSettings,
    private val outbox: Outbox,
    private val stats: CaptureStats,
    private val dispatcher: AlertDispatcher,
    private val scheduler: SyncScheduler,
    private val clock: () -> Long,
    private val requestTimeoutMs: Long = 20_000L,
) {
    private val lock = Mutex()

    suspend fun drain(maxItems: Int = 100): DrainResult = lock.withLock {
        if (!settings.onboardingDone.first()) return DrainResult.NotReady
        var maxTier: Tier? = null
        var sentAny = false
        var result = DrainResult.Done
        var processed = 0
        loop@ while (processed < maxItems) {
            val item = outbox.oldest(1).firstOrNull() ?: break
            processed++
            val r = try {
                withTimeout(requestTimeoutMs) { session.authed { api.postEvent(item.event) } }
            } catch (_: TimeoutCancellationException) {
                ApiResult.NetworkError("Timeout")
            }
            when (r) {
                is ApiResult.Success -> {
                    outbox.remove(item.id)
                    stats.add(Counter.Sent)
                    stats.markSent(clock())
                    sentAny = true
                    r.value.alert?.let { out ->
                        val alert = out.toDomain()
                        if (maxTier == null || alert.tier > maxTier!!) maxTier = alert.tier
                        dispatcher.dispatch(listOf(alert))
                    }
                }
                is ApiResult.HttpError -> when {
                    r.status == 429 -> { outbox.recordAttempt(item.id); result = DrainResult.Backoff; break@loop }
                    r.status == 401 || r.status == 408 || r.status >= 500 -> {
                        outbox.recordAttempt(item.id); result = DrainResult.Retry; break@loop
                    }
                    else -> { outbox.remove(item.id); stats.add(Counter.Dropped) } // 413, 422, other 4xx
                }
                is ApiResult.NetworkError -> { outbox.recordAttempt(item.id); result = DrainResult.Retry; break@loop }
            }
        }
        if (sentAny && FollowUpPolicy.shouldFollowUp(maxTier, clock(), clock())) {
            scheduler.scheduleFollowUps(FollowUpPolicy.DELAYS_MS)
        }
        if (result == DrainResult.Done && outbox.oldest(1).isNotEmpty()) result = DrainResult.Retry
        result
    }

    /**
     * Late-upgrade check: alerts shown in the last [lookbackMs] that were never notified. High/critical become a
     * heads-up notification; the notified-id set guarantees once per alert.
     */
    suspend fun checkAlerts(lookbackMs: Long = 15 * 60_000L): Boolean {
        if (!settings.onboardingDone.first()) return true
        val since = java.time.Instant.ofEpochMilli(clock() - lookbackMs).toString()
        return when (val r = session.authed { api.getAlerts(since) }) {
            is ApiResult.Success -> { dispatcher.dispatch(r.value.map { it.toDomain() }); true }
            is ApiResult.HttpError -> r.status in 400..499 && r.status != 401 && r.status != 429
            is ApiResult.NetworkError -> false
        }
    }
}

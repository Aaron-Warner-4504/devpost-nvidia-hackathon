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

    /** Returns the alerts that were newly notified. [ringingCall]: the alert is about a call that is ringing now. */
    suspend fun dispatch(alerts: List<Alert>, ringingCall: Boolean = false): List<Alert> = lock.withLock {
        if (alerts.isEmpty()) return emptyList()
        val seen = settings.notifiedAlertIds.first().orEmpty()
        val fresh = alerts.filter { it.id !in seen }.distinctBy { it.id }
        if (fresh.isEmpty()) return emptyList()
        val loud = fresh.filter { it.tier.interrupts }.sortedBy { it.createdAt }
        loud.forEach {
            if (ringingCall) notifier.notifyIncomingCall(it) else notifier.notify(it)
            ai.enthral.sentinel.capture.CaptureTrace.log("notified", "tier" to it.tier.name.lowercase(), "ringing" to ringingCall)
        }
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
 * Drains the outbox oldest-first (a ringing call goes first). One drain at a time (mutex), so the fast path and the worker never double-post;
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
    /**
     * Above the server's worst case (escalated events took 25-35 s on the live server). A slower answer is not lost:
     * the retry gets `duplicate: true` and the +10/+30/+90 s follow-ups fetch the alert.
     */
    private val requestTimeoutMs: Long = 50_000L,
) {
    private val lock = Mutex()

    suspend fun drain(maxItems: Int = 100): DrainResult = lock.withLock {
        if (!settings.onboardingDone.first()) return DrainResult.NotReady
        var maxTier: Tier? = null
        var sentAny = false
        var result = DrainResult.Done
        var processed = 0
        loop@ while (processed < maxItems) {
            val item = outbox.next() ?: break
            processed++
            val r = try {
                withTimeout(requestTimeoutMs) { session.authed { api.postEvent(item.event) } }
            } catch (_: TimeoutCancellationException) {
                ApiResult.NetworkError("Timeout")
            }
            when (r) {
                is ApiResult.Success -> {
                    ai.enthral.sentinel.capture.CaptureTrace.log(
                        "sent", "source" to item.event.source.name.lowercase(), "duplicate" to r.value.duplicate,
                        "tier" to (r.value.alert?.tier?.name?.lowercase() ?: "none"), "ringing" to item.ringing,
                    )
                    outbox.remove(item.id)
                    stats.add(Counter.Sent)
                    stats.markSent(clock())
                    sentAny = true
                    r.value.alert?.let { out ->
                        val alert = out.toDomain()
                        if (maxTier == null || alert.tier > maxTier!!) maxTier = alert.tier
                        dispatcher.dispatch(listOf(alert), ringingCall = item.ringing)
                    }
                }
                is ApiResult.HttpError -> when {
                    r.status == 429 -> {
                        ai.enthral.sentinel.capture.CaptureTrace.log("send_failed", "status" to 429)
                        outbox.recordAttempt(item.id); result = DrainResult.Backoff; break@loop
                    }
                    r.status == 401 || r.status == 408 || r.status >= 500 -> {
                        ai.enthral.sentinel.capture.CaptureTrace.log("send_failed", "status" to r.status)
                        outbox.recordAttempt(item.id); result = DrainResult.Retry; break@loop
                    }
                    else -> { // 413, 422, other 4xx: can never succeed
                        ai.enthral.sentinel.capture.CaptureTrace.log("dropped", "reason" to "http_${r.status}")
                        outbox.remove(item.id); stats.add(Counter.Dropped)
                    }
                }
                is ApiResult.NetworkError -> {
                    ai.enthral.sentinel.capture.CaptureTrace.log("send_failed", "status" to "network")
                    outbox.recordAttempt(item.id); result = DrainResult.Retry; break@loop
                }
            }
        }
        if (sentAny && FollowUpPolicy.shouldFollowUp(maxTier, clock(), clock())) {
            scheduler.scheduleFollowUps(FollowUpPolicy.DELAYS_MS)
        }
        if (result == DrainResult.Done && outbox.oldest(1).isNotEmpty()) result = DrainResult.Retry
        ai.enthral.sentinel.capture.CaptureTrace.log("queue", "size" to outbox.size.value, "result" to result.name)
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
            is ApiResult.Success -> {
                val fresh = dispatcher.dispatch(r.value.map { it.toDomain() })
                ai.enthral.sentinel.capture.CaptureTrace.log("follow_up", "alerts" to r.value.size, "new_loud" to fresh.size)
                true
            }
            is ApiResult.HttpError -> r.status in 400..499 && r.status != 401 && r.status != 429
            is ApiResult.NetworkError -> false
        }
    }
}

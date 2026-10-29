package ai.enthral.sentinel.capture

import ai.enthral.sentinel.data.api.EventIn
import ai.enthral.sentinel.data.store.AppSettings
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.sync.Outbox
import ai.enthral.sentinel.sync.OutboxItem
import java.time.Instant
import kotlinx.coroutines.flow.first

/** Capture runs only when every condition holds. Checked on every notification, so Pause acts instantly. */
interface CaptureGate {
    suspend fun allowed(): Boolean
}

class SettingsCaptureGate(
    private val settings: AppSettings,
    private val notificationAccess: () -> Boolean,
) : CaptureGate {
    override suspend fun allowed(): Boolean =
        settings.onboardingDone.first() &&
            settings.consentIntent(ConsentSource.Notifications).first() &&
            !settings.monitoringPaused.first() &&
            notificationAccess()
}

sealed interface CaptureOutcome {
    data object Gated : CaptureOutcome
    data class Ignored(val reason: IgnoreReason) : CaptureOutcome
    data object Duplicate : CaptureOutcome
    data object RateLimited : CaptureOutcome
    data class Queued(val event: EventIn) : CaptureOutcome
}

/**
 * notification -> gate -> allowlist/filters -> extract -> truncate -> REDACT -> sender policy -> de-dupe / rate
 * limit -> encrypted outbox -> ask for a send. Raw text exists only inside [onPosted]; only the redacted string
 * is queued. Nothing here logs.
 */
class CapturePipeline(
    private val gate: CaptureGate,
    private val filter: NotificationFilter,
    private val cursors: CursorStore,
    private val guard: CaptureGuard,
    private val senders: SenderPolicy,
    private val outbox: Outbox,
    private val stats: CaptureStats,
    private val clock: () -> Long,
    private val onQueued: () -> Unit,
) {
    suspend fun onPosted(n: RawNotification): CaptureOutcome {
        if (!gate.allowed()) return CaptureOutcome.Gated
        val source = when (val d = filter.decide(n)) {
            is FilterDecision.Ignore -> return CaptureOutcome.Ignored(d.reason)
            is FilterDecision.Accept -> d.source
        }
        val lastSeen = if (n.messages.isNotEmpty()) cursors.get(NotificationExtractor.messagingKey(n)) else null
        val ex = NotificationExtractor.extract(n, source, lastSeen)
            ?: return CaptureOutcome.Ignored(if (n.messages.isNotEmpty()) IgnoreReason.NothingNew else IgnoreReason.Empty)

        val redacted = Redactor.redact(ex.text.take(MAX_TEXT)).take(MAX_TEXT)
        when (guard.check(ex.packageName, ex.conversationKey, redacted)) {
            CaptureGuard.Verdict.Duplicate -> {
                stats.add(Counter.Duplicate)
                ex.cursorTs?.let { cursors.put(ex.conversationKey, it) }
                return CaptureOutcome.Duplicate
            }
            CaptureGuard.Verdict.RateLimited -> {
                stats.add(Counter.RateLimited)
                return CaptureOutcome.RateLimited
            }
            CaptureGuard.Verdict.Allow -> Unit
        }
        val sender = senders.resolve(ex.packageName, ex.title, ex.personUris, ex.isGroup)
        val event = EventIn(
            deviceEventId = sha256Hex("${ex.idSeed}|$redacted"),
            source = source,
            ts = Instant.ofEpochMilli(ex.timestamp).toString(),
            sender = sender.sender,
            text = redacted,
            triageScore = Triage.score(redacted),
            lang = if (redacted.any { it in 'ऀ'..'ॿ' }) "hi" else "en",
            isContact = sender.isContact,
        )
        outbox.add(OutboxItem(event, enqueuedAt = clock()))
        ex.cursorTs?.let { cursors.put(ex.conversationKey, it) }
        stats.add(Counter.Captured)
        stats.markCaptured(clock())
        onQueued()
        return CaptureOutcome.Queued(event)
    }

    companion object {
        const val MAX_TEXT = 10_000
    }
}

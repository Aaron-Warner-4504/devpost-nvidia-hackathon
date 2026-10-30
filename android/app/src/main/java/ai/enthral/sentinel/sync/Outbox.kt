package ai.enthral.sentinel.sync

import ai.enthral.sentinel.data.api.EventIn
import ai.enthral.sentinel.data.api.SentinelJson
import ai.enthral.sentinel.data.db.OutboxDao
import ai.enthral.sentinel.data.db.OutboxEntity
import ai.enthral.sentinel.data.store.TokenCipher
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.Source
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One queued event. [event] is already redacted: raw text never reaches the outbox. */
@Serializable
data class OutboxItem(
    val event: EventIn,
    val enqueuedAt: Long,
    val attempts: Int = 0,
    /** A call that is ringing right now (call screening): a high/critical answer becomes "Possible scam caller". */
    val ringing: Boolean = false,
) {
    val id: String get() = event.deviceEventId

    /** The consent that covers this event: calls under Call info, everything captured from notifications otherwise. */
    val consent: ConsentSource get() = consentOf(event.source)
}

fun consentOf(source: Source): ConsentSource = when (source) {
    Source.Call -> ConsentSource.Calls
    Source.Share -> ConsentSource.Check
    Source.Sms, Source.Whatsapp, Source.Email, Source.Notification -> ConsentSource.Notifications
}

/**
 * Delivery state of one row. pending: never tried; in_flight: being sent (a lease, so a process killed mid-send is
 * retried); failed: a retryable error, waiting for its backoff; sent: the server has it; dead: gave up (permanent
 * 4xx, too many attempts, too old or over the size cap).
 */
enum class OutboxState(val wire: String) {
    Pending("pending"), InFlight("in_flight"), Failed("failed"), Sent("sent"), Dead("dead");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

/** Numbers for the sync status UI. [nextAttemptAt]: when the earliest waiting row may be tried again. */
data class SyncCounts(
    val pending: Int = 0,
    val inFlight: Int = 0,
    val failed: Int = 0,
    val sent: Int = 0,
    val dead: Int = 0,
    val nextAttemptAt: Long? = null,
) {
    /** Still to be delivered: what "Waiting to send" shows. */
    val queued: Int get() = pending + inFlight + failed
}

/**
 * Per-item retry plan: exponential from 30 s, capped at 1 h; [MAX_ATTEMPTS] retryable failures make a row dead.
 * A 429 waits at least a minute. WorkManager's own backoff (30 s exponential, network constraint) wakes the drain.
 */
object RetryPolicy {
    const val BASE_MS = 30_000L
    const val CAP_MS = 60 * 60_000L
    const val MAX_ATTEMPTS = 8
    const val RATE_LIMIT_MS = 60_000L

    /** Wait after the [attempts]-th failure (1-based). */
    fun delayMs(attempts: Int): Long {
        val shift = (attempts - 1).coerceIn(0, 20)
        return (BASE_MS shl shift).coerceAtMost(CAP_MS)
    }
}

interface Outbox {
    /** Rows still to be delivered (pending + in_flight + failed). */
    val size: StateFlow<Int>
    val counts: StateFlow<SyncCounts>

    /** Adds unless a row with the same device_event_id exists (in any state). Returns false for a duplicate. */
    suspend fun add(item: OutboxItem): Boolean

    /** Rows still to be delivered, oldest first (after expiring rows older than the TTL). */
    suspend fun oldest(limit: Int): List<OutboxItem>

    /** Claims the next due row (a ringing call first, else the oldest) and marks it in_flight. */
    suspend fun claimNext(): OutboxItem?

    /** Delivered (or the server already had it). Keeps only ids, never the payload. */
    suspend fun markSent(id: String, serverEventId: String?)

    /** Retryable failure: backoff, or dead after [RetryPolicy.MAX_ATTEMPTS]. Returns true if the row is now dead. */
    suspend fun markFailed(id: String, status: Int?, minDelayMs: Long = 0): Boolean

    /** Can never succeed (413, 422, other 4xx). */
    suspend fun markDead(id: String, status: Int?)

    /** Consent revoked: deletes every row that consent covers, in any state. Works offline. */
    suspend fun purge(consent: ConsentSource): Int

    /** Drops everything still queued (the Settings "clear queue" action). */
    suspend fun clear(): Int

    /** device_event_id of the event the server knows as [serverEventId], if this phone sent it recently. */
    suspend fun deviceEventIdFor(serverEventId: String): String?
}

/**
 * Outbox in the encrypted Room database. Survives process death: rows are written before anything is sent, a
 * claimed row carries a lease ([leaseMs]) so a send interrupted by a kill is retried, and the stable device_event_id
 * makes the retry idempotent on the server (`duplicate: true`).
 *  - over [maxItems] queued rows: the oldest give up (counted through [onDropped]);
 *  - queued rows older than [ttlMs] give up on the next access;
 *  - sent/dead rows are pruned after [keepFinishedMs] (they hold ids and states only).
 */
class RoomOutbox(
    private val dao: suspend () -> OutboxDao,
    private val clock: () -> Long,
    private val maxItems: Int = 500,
    private val ttlMs: Long = 7L * 24 * 3600 * 1000,
    private val leaseMs: Long = 2 * 60_000L,
    private val keepFinishedMs: Long = 7L * 24 * 3600 * 1000,
    private val onDropped: suspend (Int) -> Unit = {},
) : Outbox {
    private val lock = Mutex()
    private val _size = MutableStateFlow(0)
    override val size: StateFlow<Int> = _size.asStateFlow()
    private val _counts = MutableStateFlow(SyncCounts())
    override val counts: StateFlow<SyncCounts> = _counts.asStateFlow()

    private suspend fun housekeep(d: OutboxDao) {
        val now = clock()
        val expired = d.expire(cutoff = now - ttlMs, now = now)
        if (expired > 0) onDropped(expired)
        d.pruneFinished(now - keepFinishedMs)
    }

    private suspend fun refresh(d: OutboxDao) {
        val by = d.countByState().associate { it.state to it.n }
        val c = SyncCounts(
            pending = by[OutboxState.Pending.wire] ?: 0,
            inFlight = by[OutboxState.InFlight.wire] ?: 0,
            failed = by[OutboxState.Failed.wire] ?: 0,
            sent = by[OutboxState.Sent.wire] ?: 0,
            dead = by[OutboxState.Dead.wire] ?: 0,
            nextAttemptAt = d.earliestAttemptAt(),
        )
        _counts.value = c
        _size.value = c.queued
    }

    private suspend fun <T> tx(block: suspend (OutboxDao) -> T): T = lock.withLock {
        val d = dao()
        val r = block(d)
        refresh(d)
        r
    }

    override suspend fun add(item: OutboxItem): Boolean = tx { d ->
        housekeep(d)
        val now = clock()
        val row = OutboxEntity(
            deviceEventId = item.id,
            consent = item.consent.name,
            payload = SentinelJson.encodeToString(EventIn.serializer(), item.event),
            state = OutboxState.Pending.wire,
            attempts = item.attempts,
            enqueuedAt = item.enqueuedAt,
            nextAttemptAt = now,
            updatedAt = now,
            ringing = item.ringing,
        )
        if (d.insert(row) == -1L) return@tx false
        val overflow = d.queuedCount() - maxItems
        if (overflow > 0) onDropped(d.killOldestQueued(overflow, now))
        true
    }

    override suspend fun oldest(limit: Int): List<OutboxItem> = tx { d ->
        housekeep(d)
        d.queued(limit).mapNotNull { it.toItem() }
    }

    override suspend fun claimNext(): OutboxItem? = tx { d ->
        housekeep(d)
        val now = clock()
        var row = d.nextDue(now, leaseExpiredBefore = now - leaseMs)
        while (row != null) {
            val item = row.toItem()
            if (item != null) {
                d.update(row.copy(state = OutboxState.InFlight.wire, updatedAt = now))
                return@tx item
            }
            // Unreadable payload (should not happen): never retry it.
            d.update(row.copy(state = OutboxState.Dead.wire, payload = "", updatedAt = now))
            onDropped(1)
            row = d.nextDue(now, leaseExpiredBefore = now - leaseMs)
        }
        null
    }

    override suspend fun markSent(id: String, serverEventId: String?) = tx { d ->
        val row = d.get(id) ?: return@tx
        d.update(row.copy(state = OutboxState.Sent.wire, payload = "", serverEventId = serverEventId, lastStatus = 200, updatedAt = clock()))
    }

    override suspend fun markFailed(id: String, status: Int?, minDelayMs: Long): Boolean = tx { d ->
        val row = d.get(id) ?: return@tx false
        val now = clock()
        val attempts = row.attempts + 1
        if (attempts >= RetryPolicy.MAX_ATTEMPTS) {
            d.update(row.copy(state = OutboxState.Dead.wire, payload = "", attempts = attempts, lastStatus = status, updatedAt = now))
            return@tx true
        }
        val wait = maxOf(RetryPolicy.delayMs(attempts), minDelayMs)
        d.update(row.copy(state = OutboxState.Failed.wire, attempts = attempts, lastStatus = status, nextAttemptAt = now + wait, updatedAt = now))
        false
    }

    override suspend fun markDead(id: String, status: Int?) = tx { d ->
        val row = d.get(id) ?: return@tx
        d.update(row.copy(state = OutboxState.Dead.wire, payload = "", attempts = row.attempts + 1, lastStatus = status, updatedAt = clock()))
    }

    override suspend fun purge(consent: ConsentSource): Int = tx { d -> d.deleteConsent(consent.name) }

    override suspend fun clear(): Int = tx { d -> d.deleteQueued() }

    override suspend fun deviceEventIdFor(serverEventId: String): String? = tx { d -> d.deviceEventIdFor(serverEventId) }

    /** Loads the counts eagerly (for the status card) without changing anything. */
    suspend fun warmUp() = tx { d -> housekeep(d) }

    /** Delete-my-data: every row, any state. */
    suspend fun deleteAll(): Int = tx { d -> d.deleteAll() }

    private fun OutboxEntity.toItem(): OutboxItem? = try {
        OutboxItem(SentinelJson.decodeFromString(EventIn.serializer(), payload), enqueuedAt, attempts, ringing)
    } catch (_: IllegalArgumentException) { // SerializationException is one
        null
    }
}

/**
 * One-time move of the queue kept by older builds (one Keystore-encrypted JSON file) into [RoomOutbox]. The file is
 * deleted afterwards, also when it cannot be read (key lost, corrupt): those items are gone either way.
 */
suspend fun importLegacyOutbox(file: File, cipher: TokenCipher, into: Outbox): Int {
    if (!file.exists()) return 0
    val items = try {
        val plain = cipher.decrypt(file.readBytes())
        if (plain == null) emptyList() else SentinelJson.decodeFromString(ListSerializer(OutboxItem.serializer()), plain.toString(Charsets.UTF_8))
    } catch (_: IllegalArgumentException) {
        emptyList()
    } catch (_: java.io.IOException) {
        emptyList()
    }
    var n = 0
    items.forEach { if (into.add(it)) n++ }
    file.delete()
    File(file.parentFile, file.name + ".tmp").delete()
    return n
}

package ai.enthral.sentinel.capture

import ai.enthral.sentinel.data.api.CallMeta
import ai.enthral.sentinel.data.api.EventIn
import ai.enthral.sentinel.domain.CallDirection
import ai.enthral.sentinel.domain.Source
import java.time.Instant

/** One CallLog.Calls row, as plain data. No name columns are ever read. */
data class CallRow(
    val id: Long,
    val number: String?,
    /** CallLog.Calls.DATE (ms). */
    val date: Long,
    val durationS: Long,
    /** CallLog.Calls.TYPE. */
    val type: Int,
)

/** Call metadata -> EventIn. Pure; the Android worker supplies the contact check and E.164 formatting. */
object CallEvents {
    // CallLog.Calls.*_TYPE values (stable platform constants).
    const val INCOMING = 1
    const val OUTGOING = 2
    const val MISSED = 3
    const val VOICEMAIL = 4
    const val REJECTED = 5
    const val BLOCKED = 6
    const val ANSWERED_EXTERNALLY = 7

    const val DAY_MS = 24L * 3600 * 1000

    fun direction(type: Int): CallDirection? = when (type) {
        INCOMING, ANSWERED_EXTERNALLY -> CallDirection.In
        OUTGOING -> CallDirection.Out
        MISSED, REJECTED, BLOCKED, VOICEMAIL -> CallDirection.Missed
        else -> null // unknown future types: skip rather than guess
    }

    /**
     * Where to start reading: first run -> only the last 24 h (never the whole history); later -> after the stored
     * cursor, but never more than 7 days back (a phone that was off for weeks does not replay old calls).
     */
    fun startAfter(cursor: Long?, now: Long): Long =
        if (cursor == null) now - DAY_MS else maxOf(cursor, now - 7 * DAY_MS)

    /** Hidden / private / unknown numbers come as empty or negative presentation codes. */
    fun isHidden(number: String?): Boolean =
        number.isNullOrBlank() || number.trim().startsWith("-") || number.none { it.isDigit() }

    /**
     * @param e164 formats a number to E.164 (null when it cannot); the raw number is used as a fallback.
     * @param isContact exact saved-contact check (null when unknown -> false: the schema requires a boolean).
     */
    fun toEvent(
        row: CallRow,
        e164: (String) -> String?,
        isContact: (String) -> Boolean?,
        ringing: Boolean = false,
    ): EventIn? {
        val dir = if (ringing) CallDirection.In else direction(row.type) ?: return null
        val hidden = isHidden(row.number)
        val number = if (hidden) "" else (e164(row.number!!.trim()) ?: row.number.trim()).take(SenderPolicy.MAX_SENDER)
        val contact = if (hidden) false else isContact(row.number!!.trim()) == true
        val id = if (ringing) "screen|${number}|${row.date / 1000}" else "call|${row.id}|${row.date}"
        return EventIn(
            deviceEventId = sha256Hex(id),
            source = Source.Call,
            ts = Instant.ofEpochMilli(row.date).toString(),
            sender = number.ifEmpty { null },
            text = null,
            call = CallMeta(
                number = number,
                durationS = row.durationS.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                isContact = contact,
                direction = dir,
            ),
            triageScore = 0.0,
        )
    }

    /** True when screening already reported this call (same number, rang within 2 minutes of the log entry). */
    fun alreadyScreened(number: String, date: Long, screened: Map<String, Long>): Boolean {
        val at = screened[sha256Hex(number)] ?: return false
        return kotlin.math.abs(at - date) <= 2 * 60_000L
    }
}

/** Reads CallLog.Calls rows strictly newer than [after], oldest first. */
fun interface CallLogSource {
    fun rowsAfter(after: Long, limit: Int): List<CallRow>
}

/**
 * Call log -> outbox. Runs after each call (PHONE_STATE idle + 5 s) and from the periodic worker. Gated by the
 * Call-info consent, READ_CALL_LOG, onboarding and Pause. Reads number, date, duration and type only: no names, no
 * audio, never the whole history. Calls already reported while ringing (call screening) are skipped so the server
 * does not count one call twice. A bad row is counted as Failed and skipped; the cursor still advances past it.
 */
class CallLogCapture(
    private val gate: suspend () -> Boolean,
    private val source: CallLogSource,
    private val state: CallState,
    private val outbox: ai.enthral.sentinel.sync.Outbox,
    private val stats: CaptureStats,
    private val e164: (String) -> String?,
    private val isContact: (String) -> Boolean?,
    private val clock: () -> Long,
    private val onQueued: () -> Unit,
) {
    suspend fun run(maxRows: Int = 200): Int {
        if (!gate()) {
            CaptureTrace.log("call_log", "gated" to true)
            return 0
        }
        val now = clock()
        val after = CallEvents.startAfter(state.callCursor(), now)
        val rows = source.rowsAfter(after, maxRows)
        if (rows.isEmpty()) {
            if (state.callCursor() == null) state.setCallCursor(after)
            CaptureTrace.log("call_log", "rows" to 0, "queued" to 0)
            return 0
        }
        val screened = state.screened()
        var queued = 0
        for (row in rows.sortedBy { it.date }) {
            try {
                val number = row.number?.trim().orEmpty()
                val formatted = if (CallEvents.isHidden(number)) "" else e164(number) ?: number
                if (formatted.isNotEmpty() && CallEvents.alreadyScreened(formatted, row.date, screened)) continue
                val ev = CallEvents.toEvent(row, e164, isContact) ?: continue
                if (outbox.add(ai.enthral.sentinel.sync.OutboxItem(ev, enqueuedAt = now))) {
                    CaptureTrace.log("captured", "source" to "call", "direction" to ev.call?.direction?.name?.lowercase(),
                        "is_contact" to ev.call?.isContact, "hidden" to (ev.call?.number?.isEmpty() == true))
                    queued++
                    stats.add(Counter.Captured)
                    stats.markCaptured(now)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                stats.add(Counter.Failed)
            }
        }
        state.setCallCursor(rows.maxOf { it.date })
        CaptureTrace.log("call_log", "rows" to rows.size, "queued" to queued)
        if (queued > 0) onQueued()
        return queued
    }
}

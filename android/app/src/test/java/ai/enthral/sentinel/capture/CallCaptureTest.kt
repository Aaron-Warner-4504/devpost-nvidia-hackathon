package ai.enthral.sentinel.capture

import ai.enthral.sentinel.domain.CallDirection
import ai.enthral.sentinel.domain.Source
import ai.enthral.sentinel.sync.EncryptedFileOutbox
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CallCaptureTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_761_732_000_000L
    private val e164: (String) -> String? = { PhoneNormalizer.toE164(it, "IN") }
    private val saved = setOf("+919999988888")
    private val isContact: (String) -> Boolean? = { n -> saved.any { PhoneNormalizer.sameNumber(it, n, "IN") } }

    private class MemCallState : CallState {
        var cursor: Long? = null
        val screenedMap = mutableMapOf<String, Long>()
        override suspend fun callCursor() = cursor
        override suspend fun setCallCursor(date: Long) { if ((cursor ?: Long.MIN_VALUE) < date) cursor = date }
        override suspend fun screened() = screenedMap.toMap()
        override suspend fun addScreened(number: String, at: Long) { screenedMap[sha256Hex(number)] = at }
    }

    @Test fun `directions map, unknown types are skipped`() {
        assertEquals(CallDirection.In, CallEvents.direction(CallEvents.INCOMING))
        assertEquals(CallDirection.In, CallEvents.direction(CallEvents.ANSWERED_EXTERNALLY))
        assertEquals(CallDirection.Out, CallEvents.direction(CallEvents.OUTGOING))
        listOf(CallEvents.MISSED, CallEvents.REJECTED, CallEvents.BLOCKED, CallEvents.VOICEMAIL).forEach {
            assertEquals(CallDirection.Missed, CallEvents.direction(it))
        }
        assertNull(CallEvents.direction(42))
        assertNull(CallEvents.toEvent(CallRow(1, "9876543210", now, 5, 42), e164, isContact))
    }

    @Test fun `row becomes a call event with E164 number and no text`() {
        val ev = CallEvents.toEvent(CallRow(7, "098765 43210", now, 240, CallEvents.INCOMING), e164, isContact)!!
        assertEquals(Source.Call, ev.source)
        assertEquals("+919876543210", ev.sender)
        assertNull(ev.text)
        assertEquals("+919876543210", ev.call!!.number)
        assertEquals(240, ev.call!!.durationS)
        assertFalse(ev.call!!.isContact)
        assertEquals(CallDirection.In, ev.call!!.direction)
        assertEquals("2025-10-29T10:00:00Z", ev.ts)
        assertEquals(0.0, ev.triageScore, 0.0)
        assertEquals(ev.deviceEventId, CallEvents.toEvent(CallRow(7, "098765 43210", now, 240, 1), e164, isContact)!!.deviceEventId)
    }

    @Test fun `saved contact needs an exact number match`() {
        assertTrue(CallEvents.toEvent(CallRow(1, "99999 88888", now, 1, 1), e164, isContact)!!.call!!.isContact)
        assertFalse(CallEvents.toEvent(CallRow(1, "+449999988888", now, 1, 1), e164, isContact)!!.call!!.isContact)
        assertFalse(CallEvents.toEvent(CallRow(1, "88888", now, 1, 1), e164, isContact)!!.call!!.isContact)
        // unknown contact state (no permission) is sent as false: the schema needs a boolean
        assertFalse(CallEvents.toEvent(CallRow(1, "9999988888", now, 1, 1), e164, { null })!!.call!!.isContact)
    }

    @Test fun `hidden numbers are sent without a number`() {
        listOf(null, "", "-1", "-2", "PRIVATE").forEach { n ->
            val ev = CallEvents.toEvent(CallRow(1, n, now, 0, CallEvents.MISSED), e164, isContact)!!
            assertEquals("", ev.call!!.number)
            assertNull(ev.sender)
            assertFalse(ev.call!!.isContact)
        }
    }

    @Test fun `first run reads only the last 24 hours, later runs never more than 7 days`() {
        assertEquals(now - CallEvents.DAY_MS, CallEvents.startAfter(null, now))
        assertEquals(now - 1000, CallEvents.startAfter(now - 1000, now))
        assertEquals(now - 7 * CallEvents.DAY_MS, CallEvents.startAfter(now - 30 * CallEvents.DAY_MS, now))
    }

    private fun capture(rows: List<CallRow>, state: MemCallState, gate: Boolean = true, stats: MemoryStats = MemoryStats(),
                        outbox: EncryptedFileOutbox = EncryptedFileOutbox(File(tmp.root, "o${rows.hashCode()}.bin"), XorCipher(), { now })): Triple<CallLogCapture, EncryptedFileOutbox, MutableList<Long>> {
        val asked = mutableListOf<Long>()
        val src = CallLogSource { after, limit -> asked += after; rows.filter { it.date > after }.sortedBy { it.date }.take(limit) }
        return Triple(CallLogCapture({ gate }, src, state, outbox, stats, e164, isContact, { now }, {}), outbox, asked)
    }

    @Test fun `worker queues new rows once and advances the cursor`() = runBlocking {
        val rows = listOf(
            CallRow(1, "9876543210", now - 2 * CallEvents.DAY_MS, 10, 1), // older than 24 h: never read on first run
            CallRow(2, "9876543210", now - 3_600_000, 240, 1),
            CallRow(3, "9123456789", now - 60_000, 0, 3),
        )
        val state = MemCallState()
        val (c, outbox, asked) = capture(rows, state)
        assertEquals(2, c.run())
        assertEquals(now - CallEvents.DAY_MS, asked.first())
        assertEquals(now - 60_000, state.cursor)
        assertEquals(listOf(CallDirection.In, CallDirection.Missed), outbox.oldest(10).map { it.event.call!!.direction })
        assertEquals(0, c.run())
        assertEquals(2, outbox.size.value)
    }

    @Test fun `gate off reads nothing`() = runBlocking {
        val state = MemCallState()
        val (c, outbox, asked) = capture(listOf(CallRow(1, "9876543210", now - 1000, 1, 1)), state, gate = false)
        assertEquals(0, c.run())
        assertTrue(asked.isEmpty())
        assertNull(state.cursor)
        assertEquals(0, outbox.size.value)
    }

    @Test fun `a call already reported while ringing is not sent again`() = runBlocking {
        val state = MemCallState()
        state.addScreened("+919876543210", now - 50_000)
        val (c, outbox, _) = capture(listOf(
            CallRow(1, "09876543210", now - 30_000, 120, 1),
            CallRow(2, "09876543210", now - 600_000, 30, 1), // an earlier call from the same number: still sent
        ), state)
        assertEquals(1, c.run())
        assertEquals(30, outbox.oldest(10).single().event.call!!.durationS)
    }

    @Test fun `a broken row is counted and skipped, the rest is captured`() = runBlocking {
        val stats = MemoryStats()
        val state = MemCallState()
        val asked = mutableListOf<Long>()
        val rows = listOf(CallRow(1, "5550000000", now - 2000, 1, 1), CallRow(2, "9876543210", now - 1000, 1, 1))
        val outbox = EncryptedFileOutbox(File(tmp.root, "b.bin"), XorCipher(), { now })
        val c = CallLogCapture({ true }, { a, _ -> asked += a; rows }, state, outbox, stats,
            { if (it == "5550000000") throw IllegalStateException("x") else e164(it) }, isContact, { now }, {})
        assertEquals(1, c.run())
        assertEquals(1L, stats[Counter.Failed])
        assertEquals(now - 1000, state.cursor)
    }

    @Test fun `ringing event uses a distinct id`() {
        val row = CallRow(0, "9876543210", now, 0, CallEvents.INCOMING)
        val ring = CallEvents.toEvent(row, e164, isContact, ringing = true)!!
        val log = CallEvents.toEvent(row.copy(id = 9), e164, isContact)!!
        assertTrue(ring.deviceEventId != log.deviceEventId)
        assertEquals(CallDirection.In, ring.call!!.direction)
        assertTrue(CallEvents.alreadyScreened("+919876543210", now + 90_000, mapOf(sha256Hex("+919876543210") to now)))
        assertFalse(CallEvents.alreadyScreened("+919876543210", now + 200_000, mapOf(sha256Hex("+919876543210") to now)))
    }
}

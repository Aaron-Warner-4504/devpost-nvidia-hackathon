package ai.enthral.sentinel.capture

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.CallMeta
import ai.enthral.sentinel.data.api.EventIn
import ai.enthral.sentinel.data.api.EventResult
import ai.enthral.sentinel.data.api.SentinelJson
import ai.enthral.sentinel.data.db.RoomLocalStore
import ai.enthral.sentinel.data.repo.ApiDeviceSession
import ai.enthral.sentinel.domain.CallDirection
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.Source
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.sync.AlertDispatcher
import ai.enthral.sentinel.sync.DrainResult
import ai.enthral.sentinel.sync.EventSender
import ai.enthral.sentinel.sync.OutboxItem
import ai.enthral.sentinel.sync.RetryPolicy
import ai.enthral.sentinel.sync.RoomOutbox
import ai.enthral.sentinel.sync.importLegacyOutbox
import ai.enthral.sentinel.testing.FakeNotifier
import ai.enthral.sentinel.testing.FakeSettings
import ai.enthral.sentinel.testing.testTokenStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Room outbox state machine: pending -> in_flight -> sent | failed (backoff) -> dead. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomOutboxTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_700_000_000_000L
    private var dropped = 0

    private fun outbox(max: Int = 500) = testOutbox({ now }, onDropped = { dropped += it }, maxItems = max)

    @Test fun `dedupe by device event id, also after it was sent`() = runBlocking {
        val o = outbox()
        assertTrue(o.add(OutboxItem(event("e1"), now)))
        assertFalse(o.add(OutboxItem(event("e1"), now + 5)))
        assertEquals("e1", o.claimNext()!!.id)
        o.markSent("e1", "srv1")
        assertFalse(o.add(OutboxItem(event("e1"), now + 9))) // a re-captured notification is not sent twice
        assertEquals(0, o.size.value)
        assertEquals(1, o.counts.value.sent)
        assertEquals("e1", o.deviceEventIdFor("srv1"))
    }

    @Test fun `claim moves to in flight, a ringing call goes first`() = runBlocking {
        val o = outbox()
        o.add(OutboxItem(event("old"), now - 10))
        o.add(OutboxItem(event("ring"), now, ringing = true))
        assertEquals("ring", o.claimNext()!!.id)
        assertEquals(1, o.counts.value.inFlight)
        assertEquals("old", o.claimNext()!!.id)
        assertNull(o.claimNext())
    }

    @Test fun `failures back off exponentially then die after max attempts`() = runBlocking {
        val o = outbox()
        o.add(OutboxItem(event("a"), now))
        for (attempt in 1 until RetryPolicy.MAX_ATTEMPTS) {
            assertEquals("a", o.claimNext()!!.id)
            assertFalse(o.markFailed("a", 503))
            assertEquals(1, o.counts.value.failed)
            assertEquals(now + RetryPolicy.delayMs(attempt), o.counts.value.nextAttemptAt)
            now += RetryPolicy.delayMs(attempt) - 1
            assertNull("not due before its backoff", o.claimNext())
            now += 1
        }
        assertEquals(RetryPolicy.MAX_ATTEMPTS - 1, o.oldest(1).single().attempts)
        o.claimNext()
        assertTrue(o.markFailed("a", null))
        assertEquals(1, o.counts.value.dead)
        assertEquals(0, o.size.value)
        assertNull(o.claimNext())
    }

    @Test fun `backoff grows and is capped`() {
        assertEquals(30_000L, RetryPolicy.delayMs(1))
        assertEquals(60_000L, RetryPolicy.delayMs(2))
        assertEquals(120_000L, RetryPolicy.delayMs(3))
        assertEquals(RetryPolicy.CAP_MS, RetryPolicy.delayMs(30))
    }

    @Test fun `a send interrupted by process death is retried after the lease, from disk`() = runBlocking {
        val name = "sentinel-test-${System.nanoTime()}.db"
        val db1 = testDatabase(name)
        val o1 = RoomOutbox({ db1.outboxDao() }, { now })
        o1.add(OutboxItem(event("a", "masked text"), now))
        o1.add(OutboxItem(event("b"), now))
        assertEquals("a", o1.claimNext()!!.id) // in flight when the process dies
        db1.close()

        val db2 = testDatabase(name) // new process, same file
        val o2 = RoomOutbox({ db2.outboxDao() }, { now })
        assertEquals(listOf("a", "b"), o2.oldest(10).map { it.id })
        assertEquals("masked text", o2.oldest(1).single().event.text)
        assertEquals("b", o2.claimNext()!!.id) // a still holds its lease
        now += 2 * 60_000L
        assertEquals("a", o2.claimNext()!!.id) // lease expired: retried with the same id (server dedupes)
        db2.close()
    }

    @Test fun `revocation purges every row of that consent, other sources stay`() = runBlocking {
        val o = outbox()
        o.add(OutboxItem(event("sms1"), now))
        o.add(OutboxItem(callEvent("call1"), now))
        o.add(OutboxItem(event("sms2"), now))
        o.claimNext() // sms1 in flight
        assertEquals(2, o.purge(ConsentSource.Notifications))
        assertEquals(listOf("call1"), o.oldest(10).map { it.id })
        assertEquals(1, o.purge(ConsentSource.Calls))
        assertEquals(0, o.size.value)
    }

    @Test fun `size cap and TTL give up the oldest`() = runBlocking {
        val o = outbox(max = 3)
        listOf("a", "b", "c", "d", "e").forEach { o.add(OutboxItem(event(it), now)) }
        assertEquals(listOf("c", "d", "e"), o.oldest(10).map { it.id })
        assertEquals(2, dropped)
        now += 7L * 24 * 3600 * 1000 + 1
        assertTrue(o.oldest(10).isEmpty())
        assertEquals(5, dropped)
    }

    @Test fun `old encrypted queue file is imported once and deleted`() = runBlocking {
        val f = File(tmp.root, "outbox.bin")
        val cipher = XorCipher()
        val items = listOf(OutboxItem(event("x"), now), OutboxItem(event("y"), now))
        f.writeBytes(cipher.encrypt(SentinelJson.encodeToString(ListSerializer(OutboxItem.serializer()), items).toByteArray()))
        val o = outbox()
        assertEquals(2, importLegacyOutbox(f, cipher, o))
        assertFalse(f.exists())
        assertEquals(listOf("x", "y"), o.oldest(10).map { it.id })
    }
}

/** EventSender over the Room outbox: retry, dedupe, revocation at send time, upgrade-only reconcile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_700_000_000_000L
    private val api = FakeApi()
    private val settings = FakeSettings()
    private val stats = MemoryStats()
    private val db = testDatabase()
    private val store = RoomLocalStore(db)
    private lateinit var outbox: RoomOutbox
    private var consentOn = true
    private var synced = 0

    @Before fun setUp() = runBlocking {
        settings.done.value = true
        settings.notified.value = emptySet()
        outbox = RoomOutbox({ db.outboxDao() }, { now })
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun sender() = EventSender(
        api, ApiDeviceSession(api, testTokenStore(File(tmp.root, "t${System.nanoTime()}.preferences_pb"), scope)),
        settings, outbox, stats, AlertDispatcher(settings, FakeNotifier()), RecordingScheduler(), { now },
        requestTimeoutMs = 500, consentGranted = { consentOn }, localStore = store, onSynced = { synced++ },
    )

    @Test fun `a revoked consent is purged at send time, nothing is posted`() = runBlocking {
        outbox.add(OutboxItem(event("a"), now))
        consentOn = false
        assertEquals(DrainResult.Done, sender().drain())
        assertTrue(api.posted.isEmpty())
        assertEquals(0, outbox.size.value)
        assertEquals(0, outbox.counts.value.sent + outbox.counts.value.dead)
    }

    @Test fun `server duplicate is delivered and the same id is never posted again`() = runBlocking {
        api.answer({ ApiResult.Success(EventResult("srv", true, null)) })
        outbox.add(OutboxItem(event("a"), now))
        val s = sender()
        assertEquals(DrainResult.Done, s.drain())
        assertFalse(outbox.add(OutboxItem(event("a"), now)))
        assertEquals(DrainResult.Done, s.drain())
        assertEquals(1, api.posted.size)
        assertEquals(1, synced)
    }

    @Test fun `retry keeps the same device event id until it succeeds`() = runBlocking {
        api.answer(offline, http(500), ok())
        outbox.add(OutboxItem(event("a"), now))
        val s = sender()
        assertEquals(DrainResult.Retry, s.drain())
        now += RetryPolicy.delayMs(1)
        assertEquals(DrainResult.Retry, s.drain())
        now += RetryPolicy.delayMs(2)
        assertEquals(DrainResult.Done, s.drain())
        assertEquals(listOf("a", "a", "a"), api.posted.map { it.deviceEventId })
        assertEquals(1, outbox.counts.value.sent)
    }

    @Test fun `422 is dead at once, never retried`() = runBlocking {
        api.answer(http(422))
        outbox.add(OutboxItem(event("a"), now))
        assertEquals(DrainResult.Done, sender().drain())
        assertEquals(1, outbox.counts.value.dead)
        assertEquals(1L, stats[Counter.Dropped])
    }

    @Test fun `server result upgrades the local assessment and never downgrades it`() = runBlocking {
        store.recordAssessment("hi", Tier.High, listOf("urgency"), setOf("pressure"), "offline check, fewer signals", "local")
        store.recordAssessment("lo", Tier.Info, emptyList(), emptySet(), "offline check, fewer signals", "local")
        api.answer({ ok(alertOut("al-hi", Tier.Caution))(it) }, { ok(alertOut("al-lo", Tier.Critical))(it) })
        outbox.add(OutboxItem(event("hi"), now))
        outbox.add(OutboxItem(event("lo"), now + 1))
        sender().drain()
        assertEquals("high", store.getAssessment("hi")!!.tier) // server said caution: kept high
        assertEquals("offline check, fewer signals", store.getAssessment("hi")!!.explanation)
        assertEquals("critical", store.getAssessment("lo")!!.tier)
        assertEquals("server", store.getAssessment("lo")!!.origin)
    }

    @Test fun `a late alert from the list is mapped back to the device event and upgrades it`() = runBlocking {
        outbox.add(OutboxItem(event("a"), now))
        val s = sender()
        api.answer(ok()) // server event id "srv_a"
        s.drain()
        api.alertsAnswer = ApiResult.Success(listOf(alertOut("late", Tier.Critical).copy(eventId = "srv_a")))
        assertTrue(s.checkAlerts())
        assertEquals("critical", store.getAssessment("a")!!.tier)
    }
}

private fun callEvent(id: String) = EventIn(
    deviceEventId = id, source = Source.Call, ts = "2026-10-29T10:00:00Z", sender = "+919876543210",
    call = CallMeta("+919876543210", 60, false, CallDirection.In), triageScore = 0.0,
)

package ai.enthral.sentinel.capture

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.EventResult
import ai.enthral.sentinel.data.repo.ApiDeviceSession
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.sync.AlertDispatcher
import ai.enthral.sentinel.sync.DrainResult
import ai.enthral.sentinel.sync.RoomOutbox
import ai.enthral.sentinel.sync.RetryPolicy
import ai.enthral.sentinel.sync.EventSender
import ai.enthral.sentinel.sync.FollowUpPolicy
import ai.enthral.sentinel.sync.OutboxItem
import ai.enthral.sentinel.testing.FakeNotifier
import ai.enthral.sentinel.testing.FakeSettings
import ai.enthral.sentinel.testing.testTokenStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class EventSenderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_700_000_000_000L
    private val api = FakeApi()
    private val settings = FakeSettings()
    private val notifier = FakeNotifier()
    private val stats = MemoryStats()
    private val scheduler = RecordingScheduler()
    private lateinit var outbox: RoomOutbox
    private lateinit var sender: EventSender
    private lateinit var session: ApiDeviceSession

    @Before fun setUp() = runBlocking {
        settings.done.value = true
        settings.notified.value = emptySet()
        outbox = testOutbox({ now }, onDropped = { stats.add(Counter.Dropped, it.toLong()) })
        session = ApiDeviceSession(api, testTokenStore(File(tmp.root, "t.preferences_pb"), scope))
        sender = EventSender(api, session, settings, outbox, stats, AlertDispatcher(settings, notifier), scheduler, { now }, requestTimeoutMs = 500)
    }

    @After fun tearDown() = scope.cancel()

    private suspend fun queue(vararg ids: String) = ids.forEach { outbox.add(OutboxItem(event(it), now)) }

    @Test fun `drains oldest first and follows up`() = runBlocking {
        queue("a", "b", "c")
        assertEquals(DrainResult.Done, sender.drain())
        assertEquals(listOf("a", "b", "c"), api.posted.map { it.deviceEventId })
        assertEquals(0, outbox.size.value)
        assertEquals(3L, stats[Counter.Sent])
        assertEquals(listOf(FollowUpPolicy.DELAYS_MS), scheduler.followUps)
    }

    @Test fun `nothing is sent before onboarding is finished`() = runBlocking {
        settings.done.value = false
        queue("a")
        assertEquals(DrainResult.NotReady, sender.drain())
        assertTrue(api.posted.isEmpty())
        assertEquals(1, outbox.size.value)
    }

    @Test fun `critical alert notifies once, caution stays silent`() = runBlocking {
        api.answer(ok(alertOut("al1", Tier.Caution)), ok(alertOut("al2", Tier.Critical)), ok(alertOut("al2", Tier.Critical)))
        queue("a", "b", "c")
        sender.drain()
        assertEquals(listOf("al2"), notifier.notified.map { it.id })
        assertTrue(settings.notified.value!!.containsAll(setOf("al1", "al2")))
        // late follow-up returns the same alerts again: no second notification
        api.alertsAnswer = ApiResult.Success(listOf(alertOut("al2", Tier.Critical), alertOut("al1", Tier.Caution)))
        assertTrue(sender.checkAlerts())
        assertEquals(1, notifier.notified.size)
    }

    @Test fun `follow up surfaces a late upgrade once`() = runBlocking {
        api.alertsAnswer = ApiResult.Success(listOf(alertOut("up1", Tier.High), alertOut("c1", Tier.Caution)))
        assertTrue(sender.checkAlerts())
        assertTrue(sender.checkAlerts())
        assertEquals(listOf("up1"), notifier.notified.map { it.id })
        val since = java.time.Instant.parse(api.alertSinces.first()!!)
        assertEquals(now - 15 * 60_000L, since.toEpochMilli())
    }

    @Test fun `follow up policy`() {
        assertTrue(FollowUpPolicy.shouldFollowUp(Tier.Caution, null, now))
        assertFalse(FollowUpPolicy.shouldFollowUp(Tier.Info, null, now))
        assertTrue(FollowUpPolicy.shouldFollowUp(null, now - 30_000, now))
        assertFalse(FollowUpPolicy.shouldFollowUp(null, now - 61_000, now))
        assertEquals(listOf(15_000L, 45_000L, 120_000L), FollowUpPolicy.DELAYS_MS)
    }

    @Test fun `422 and 413 drop the item and count it, the rest continues`() = runBlocking {
        api.answer(http(422), http(413), ok())
        queue("a", "b", "c")
        assertEquals(DrainResult.Done, sender.drain())
        assertEquals(2L, stats[Counter.Dropped])
        assertEquals(1L, stats[Counter.Sent])
        assertEquals(0, outbox.size.value)
    }

    @Test fun `429 backs off and keeps the item`() = runBlocking {
        api.answer(http(429))
        queue("a", "b")
        assertEquals(DrainResult.Backoff, sender.drain())
        assertEquals(1, api.posted.size)
        assertEquals(listOf("a", "b"), outbox.oldest(5).map { it.id })
        assertEquals(1, outbox.oldest(1).single().attempts)
    }

    @Test fun `5xx, network and timeouts keep the item for a retry with the same id`() = runBlocking {
        api.answer(http(503), offline, ok())
        queue("a")
        assertEquals(DrainResult.Retry, sender.drain())
        assertEquals(DrainResult.Retry, sender.drain()) // still backing off: not re-posted yet
        assertEquals(1, api.posted.size)
        now += RetryPolicy.delayMs(1)
        assertEquals(DrainResult.Retry, sender.drain())
        now += RetryPolicy.delayMs(2)
        assertEquals(DrainResult.Done, sender.drain())
        assertEquals(listOf("a", "a", "a"), api.posted.map { it.deviceEventId })
        api.hang = true
        queue("slow")
        assertEquals(DrainResult.Retry, sender.drain())
        assertEquals(1, outbox.size.value)
    }

    @Test fun `401 re-registers once and retries`() = runBlocking {
        api.answer(http(401), ok())
        queue("a")
        assertEquals(DrainResult.Done, sender.drain())
        assertEquals(2, api.registerCalls) // first registration + one re-register after the 401
        assertEquals(2, api.posted.size)
        assertEquals(0, outbox.size.value)
    }

    @Test fun `persistent 401 keeps the item`() = runBlocking {
        api.answer(http(401))
        queue("a")
        assertEquals(DrainResult.Retry, sender.drain())
        assertEquals(1, outbox.size.value)
    }

    @Test fun `a ringing call jumps the queue and its alert is a scam-caller warning`() = runBlocking {
        val calls = mutableListOf<String>()
        val n = object : ai.enthral.sentinel.data.notify.AlertNotifier {
            override fun ensureChannels() = Unit
            override fun canNotify() = true
            override fun notify(alert: ai.enthral.sentinel.domain.Alert) { calls += "notify:${alert.id}" }
            override fun notifyIncomingCall(alert: ai.enthral.sentinel.domain.Alert) { calls += "call:${alert.id}" }
        }
        val s2 = EventSender(api, session, settings, outbox, stats, AlertDispatcher(settings, n), scheduler, { now })
        queue("old1", "old2")
        outbox.add(OutboxItem(event("ring"), now, ringing = true))
        api.answer({ ev -> ok(alertOut("a-${ev.deviceEventId}", if (ev.deviceEventId == "ring") Tier.Critical else Tier.High))(ev) })
        s2.drain()
        assertEquals(listOf("ring", "old1", "old2"), api.posted.map { it.deviceEventId })
        assertEquals(listOf("call:a-ring", "notify:a-old1", "notify:a-old2"), calls)
    }

    @Test fun `server duplicate counts as delivered`() = runBlocking {
        api.answer({ ApiResult.Success(EventResult("srv", true, null)) })
        queue("a")
        assertEquals(DrainResult.Done, sender.drain())
        assertEquals(0, outbox.size.value)
    }
}

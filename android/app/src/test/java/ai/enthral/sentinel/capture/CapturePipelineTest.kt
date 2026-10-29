package ai.enthral.sentinel.capture

import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.Source
import ai.enthral.sentinel.sync.EncryptedFileOutbox
import ai.enthral.sentinel.testing.FakeSettings
import ai.enthral.sentinel.ui.common.CaptureState
import ai.enthral.sentinel.ui.common.SourcesState
import ai.enthral.sentinel.ui.common.captureState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CapturePipelineTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_761_732_000_000L
    private val settings = FakeSettings()
    private var access = true
    private val stats = MemoryStats()
    private var queuedCalls = 0
    private lateinit var outbox: EncryptedFileOutbox
    private lateinit var pipeline: CapturePipeline
    private val pseudo = Pseudonymizer(ByteArray(32) { 3 })

    @Before fun setUp() = runBlocking {
        settings.done.value = true
        settings.setConsentIntent(ConsentSource.Notifications, true)
        outbox = EncryptedFileOutbox(File(tmp.root, "o.bin"), XorCipher(), { now })
        pipeline = CapturePipeline(
            gate = SettingsCaptureGate(settings) { access },
            filter = NotificationFilter(CaptureAllowlist.release + ("com.android.shell" to Source.Sms), "ai.enthral.sentinel"),
            cursors = stats,
            guard = CaptureGuard({ now }),
            senders = SenderPolicy(pseudo, FakeContacts(names = setOf("Mummy"))),
            outbox = outbox,
            stats = stats,
            clock = { now },
            onQueued = { queuedCalls++ },
        )
    }

    private fun sms(text: String, title: String = "VM-HDFCBK", key: String = "k1", postTime: Long = now) =
        RawNotification("com.android.shell", key, postTime, title = title, text = text)

    @Test fun `bank OTP is masked before it is queued and nothing raw reaches disk`() = runBlocking {
        val out = pipeline.onPosted(sms("Your OTP is 482913. Do not share it with anyone. -HDFC Bank"))
        val ev = (out as CaptureOutcome.Queued).event
        assertEquals("Your OTP is [OTP]. Do not share it with anyone. -HDFC Bank", ev.text)
        assertEquals("VM-HDFCBK", ev.sender)
        assertEquals(Source.Sms, ev.source)
        assertEquals(false, ev.isContact)
        assertEquals("en", ev.lang)
        assertEquals(64, ev.deviceEventId.length)
        assertEquals("2025-10-29T10:00:00Z", ev.ts)
        assertFalse(File(tmp.root, "o.bin").readBytes().toString(Charsets.ISO_8859_1).contains("482913"))
        assertFalse(outbox.oldest(10).single().event.text!!.contains("482913"))
        assertEquals(1L, stats[Counter.Captured])
        assertEquals(now, stats.lastCaptured)
        assertEquals(1, queuedCalls)
    }

    @Test fun `gate blocks without onboarding, consent, access, or while paused`() = runBlocking {
        settings.paused.value = true
        assertEquals(CaptureOutcome.Gated, pipeline.onPosted(sms("a")))
        settings.paused.value = false
        access = false
        assertEquals(CaptureOutcome.Gated, pipeline.onPosted(sms("a")))
        access = true
        settings.setConsentIntent(ConsentSource.Notifications, false)
        assertEquals(CaptureOutcome.Gated, pipeline.onPosted(sms("a")))
        settings.setConsentIntent(ConsentSource.Notifications, true)
        settings.done.value = false
        assertEquals(CaptureOutcome.Gated, pipeline.onPosted(sms("a")))
        assertEquals(0, outbox.size.value)
        settings.done.value = true
        assertTrue(pipeline.onPosted(sms("a")) is CaptureOutcome.Queued)
    }

    @Test fun `re-posted identical notification is deduplicated, a changed one is not`() = runBlocking {
        assertTrue(pipeline.onPosted(sms("Parcel held, call 9876543210")) is CaptureOutcome.Queued)
        assertEquals(CaptureOutcome.Duplicate, pipeline.onPosted(sms("Parcel held, call 9876543210", postTime = now + 5)))
        assertTrue(pipeline.onPosted(sms("Parcel held, call 9876543210 today")) is CaptureOutcome.Queued)
        assertEquals(2, outbox.size.value)
        assertEquals(1L, stats[Counter.Duplicate])
    }

    @Test fun `event id is stable for retries and differs per notification`() = runBlocking {
        val a = (pipeline.onPosted(sms("one", key = "k1")) as CaptureOutcome.Queued).event.deviceEventId
        now += 11 * 60_000
        val again = (pipeline.onPosted(sms("one", key = "k1", postTime = 1_761_732_000_000L)) as CaptureOutcome.Queued).event.deviceEventId
        val other = (pipeline.onPosted(sms("one", key = "k2", postTime = 1_761_732_000_000L)) as CaptureOutcome.Queued).event.deviceEventId
        assertEquals(a, again)
        assertTrue(a != other)
        assertEquals(2, outbox.size.value) // same id is not queued twice
    }

    @Test fun `text is truncated to 10000 chars`() = runBlocking {
        val ev = (pipeline.onPosted(sms("x".repeat(20_000))) as CaptureOutcome.Queued).event
        assertEquals(10_000, ev.text!!.length)
    }

    @Test fun `rate limit counts and stops queueing`() = runBlocking {
        repeat(20) { assertTrue(pipeline.onPosted(sms("msg $it", key = "k$it")) is CaptureOutcome.Queued) }
        assertEquals(CaptureOutcome.RateLimited, pipeline.onPosted(sms("msg x", key = "kx")))
        assertEquals(1L, stats[Counter.RateLimited])
        assertEquals(20, outbox.size.value)
    }

    @Test fun `messaging updates send only new messages and contact names become pseudonyms`() = runBlocking {
        val m1 = RawMessage("hi beta", now - 2000, "Mummy", null)
        val m2 = RawMessage("send 5000 to this upi mom@ybl", now - 1000, "Mummy", null)
        val first = RawNotification("com.whatsapp", "wa1", now, title = "Mummy", text = "2 messages", messages = listOf(m1, m2))
        val e1 = (pipeline.onPosted(first) as CaptureOutcome.Queued).event
        assertEquals("hi beta\nsend 5000 to this upi mom@ybl", e1.text)
        assertEquals(pseudo.pseudonym("Mummy"), e1.sender)
        assertFalse(e1.sender!!.contains("Mummy"))
        assertEquals(false, e1.isContact) // a matching name is not proof
        assertEquals(Source.Whatsapp, e1.source)
        // same notification posted again: nothing new
        assertEquals(CaptureOutcome.Ignored(IgnoreReason.NothingNew), pipeline.onPosted(first.copy(postTime = now + 1)))
        val m3 = RawMessage("urgent", now + 500, "Mummy", null)
        val e2 = (pipeline.onPosted(first.copy(messages = listOf(m1, m2, m3), postTime = now + 600)) as CaptureOutcome.Queued).event
        assertEquals("urgent", e2.text)
    }

    @Test fun `hindi text is tagged hi and devanagari OTPs are masked`() = runBlocking {
        val ev = (pipeline.onPosted(sms("आपका ओटीपी ४८२९१३ है")) as CaptureOutcome.Queued).event
        assertEquals("hi", ev.lang)
        assertFalse(ev.text!!.contains("४८२९१३"))
    }

    @Test fun `not allowlisted and empty notifications are ignored without side effects`() = runBlocking {
        assertEquals(CaptureOutcome.Ignored(IgnoreReason.NotAllowlisted), pipeline.onPosted(sms("x").copy(packageName = "com.instagram.android")))
        assertEquals(CaptureOutcome.Ignored(IgnoreReason.Empty), pipeline.onPosted(sms(" ")))
        assertEquals(0, outbox.size.value)
        assertEquals(0, queuedCalls)
    }

    @Test fun `hostile notifications never throw out of the pipeline`() = runBlocking {
        val angry = FakeContacts().apply { throwOnQuery = true }
        val p2 = CapturePipeline(
            SettingsCaptureGate(settings) { true },
            NotificationFilter(CaptureAllowlist.release + ("com.android.shell" to Source.Sms), "ai.enthral.sentinel"),
            stats, CaptureGuard({ now }), SenderPolicy(pseudo, angry), outbox, stats, { now }, {},
        )
        val goodUri = "content://com.android.contacts/contacts/lookup/0r1-AB/17"
        val hostile = listOf(
            RawNotification("com.whatsapp", "h1", now, title = "Ravi", text = "x", peopleUris = listOf(goodUri)),
            RawNotification("com.whatsapp", "h2", now, title = "\uD800broken", text = "\uDFFF\u0000\uFFFF".repeat(5000)),
            RawNotification("com.whatsapp", "h3", now, title = null, text = "a".repeat(2_000_000)),
            RawNotification("com.whatsapp", "h4", now, title = "R", text = "x",
                peopleUris = listOf("tel:%ZZ", "tel:%C3%28", "content://com.android.contacts/%", ":::", "x".repeat(100_000))),
            RawNotification("com.whatsapp", "h5", now, messages = listOf(RawMessage(null, -1, null, "tel:%"), RawMessage("", Long.MAX_VALUE, "", ""))),
        )
        val outcomes = hostile.map { p2.onPostedSafely(it) }
        assertEquals(CaptureOutcome.Failed, outcomes[0]) // the provider threw: counted, not crashed
        assertTrue(outcomes.drop(1).none { it == CaptureOutcome.Failed })
        assertEquals(1L, stats[Counter.Failed])
        assertTrue(outbox.oldest(100).all { it.event.text!!.length <= 10_000 })
    }

    @Test fun `capture state for the status card`() {
        val on = SourcesState(notificationsIntent = true, notificationAccess = true)
        assertEquals(CaptureState.Active, captureState(on, paused = false))
        assertEquals(CaptureState.Paused, captureState(on, paused = true))
        assertEquals(CaptureState.NeedsAccess, captureState(on.copy(notificationAccess = false), paused = false))
        assertEquals(CaptureState.Off, captureState(on.copy(notificationsIntent = false), paused = false))
    }

    @Test fun `triage needs two kinds of cue to ask for the agent`() {
        assertTrue(Triage.score("Your OTP is [OTP]. -HDFC Bank") < 0.5)
        assertTrue(Triage.score("Your parcel is held, visit http://x.in") < 0.5)
        assertTrue(Triage.score("Customs officer: pay Rs 25,000 penalty immediately or face arrest. Do not tell anyone.") >= 0.5)
        assertEquals(0.0, Triage.score(""), 0.0)
    }

    @Test fun `sms header and phone shapes`() {
        assertTrue(SenderPolicy.looksLikeSmsHeader("VM-HDFCBK"))
        assertTrue(SenderPolicy.looksLikePhone("+91 98765 43210"))
        assertFalse(SenderPolicy.looksLikePhone("Ravi 98"))
        assertNull(PhoneNormalizer.toE164("12", "IN"))
    }
}

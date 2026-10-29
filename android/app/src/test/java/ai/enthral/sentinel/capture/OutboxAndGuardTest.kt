package ai.enthral.sentinel.capture

import ai.enthral.sentinel.sync.EncryptedFileOutbox
import ai.enthral.sentinel.sync.OutboxItem
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureGuardTest {
    private var now = 0L
    private val guard = CaptureGuard({ now })

    @Test fun `same package, key and text within 10 minutes is a duplicate`() {
        assertEquals(CaptureGuard.Verdict.Allow, guard.check("p", "k", "t"))
        now += 9 * 60_000
        assertEquals(CaptureGuard.Verdict.Duplicate, guard.check("p", "k", "t"))
        assertEquals(CaptureGuard.Verdict.Allow, guard.check("p", "k", "t2"))
        assertEquals(CaptureGuard.Verdict.Allow, guard.check("p", "k2", "t"))
        assertEquals(CaptureGuard.Verdict.Allow, guard.check("q", "k", "t"))
        now += 60_001
        assertEquals(CaptureGuard.Verdict.Allow, guard.check("p", "k", "t"))
    }

    @Test fun `at most 20 per package per 10 minutes, sliding`() {
        repeat(20) { assertEquals(CaptureGuard.Verdict.Allow, guard.check("p", "k$it", "t")) }
        assertEquals(CaptureGuard.Verdict.RateLimited, guard.check("p", "k99", "t"))
        assertEquals(CaptureGuard.Verdict.Allow, guard.check("other", "k99", "t"))
        assertEquals(mapOf("p" to 20, "other" to 1), guard.counts())
        now += 10 * 60_000
        assertEquals(CaptureGuard.Verdict.Allow, guard.check("p", "k100", "t"))
    }

    @Test fun `rate limited items are not remembered as seen`() {
        repeat(20) { guard.check("p", "k$it", "x") }
        assertEquals(CaptureGuard.Verdict.RateLimited, guard.check("p", "late", "x"))
        now += 10 * 60_000
        assertEquals(CaptureGuard.Verdict.Allow, guard.check("p", "late", "x"))
    }
}

class OutboxTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000L
    private val cipher = XorCipher()
    private var dropped = 0
    private fun outbox(max: Int = 500) =
        EncryptedFileOutbox(File(tmp.root, "q/outbox.bin"), cipher, { now }, maxItems = max, onDropped = { dropped += it })

    @Test fun `round trip survives a restart and is encrypted on disk`() = runTest {
        val a = outbox()
        assertTrue(a.add(OutboxItem(event("e1", "secret-marker-text"), now)))
        assertTrue(a.add(OutboxItem(event("e2"), now + 1)))
        val raw = File(tmp.root, "q/outbox.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("secret-marker-text"))
        assertFalse(raw.contains("deviceEventId") || raw.contains("device_event_id"))
        val b = outbox()
        assertEquals(listOf("e1", "e2"), b.oldest(10).map { it.id })
        assertEquals("secret-marker-text", b.oldest(1).single().event.text)
        assertFalse(File(tmp.root, "q/outbox.bin.tmp").exists())
    }

    @Test fun `duplicate ids are ignored`() = runTest {
        val o = outbox()
        assertTrue(o.add(OutboxItem(event("e1"), now)))
        assertFalse(o.add(OutboxItem(event("e1"), now + 5)))
        assertEquals(1, o.size.value)
    }

    @Test fun `oldest first and remove, attempts recorded`() = runTest {
        val o = outbox()
        listOf("a", "b", "c").forEach { o.add(OutboxItem(event(it), now)) }
        o.recordAttempt("a")
        assertEquals(1, o.oldest(1).single().attempts)
        o.remove("a")
        assertEquals(listOf("b", "c"), o.oldest(5).map { it.id })
        assertEquals(2, outbox().oldest(5).size)
    }

    @Test fun `size cap drops the oldest and counts it`() = runTest {
        val o = outbox(max = 3)
        listOf("a", "b", "c", "d", "e").forEach { o.add(OutboxItem(event(it), now)) }
        assertEquals(listOf("c", "d", "e"), o.oldest(10).map { it.id })
        assertEquals(2, dropped)
    }

    @Test fun `items older than 7 days expire`() = runTest {
        val o = outbox()
        o.add(OutboxItem(event("old"), now))
        now += 3L * 24 * 3600 * 1000
        o.add(OutboxItem(event("new"), now))
        now += 4L * 24 * 3600 * 1000 + 1
        assertEquals(listOf("new"), o.oldest(10).map { it.id })
        assertEquals(1, dropped)
        assertEquals(1, outbox().oldest(10).size)
    }

    @Test fun `garbage or undecryptable file restarts empty without crashing`() = runTest {
        File(tmp.root, "q").mkdirs()
        File(tmp.root, "q/outbox.bin").writeBytes(byteArrayOf(0x53, 0x45, 1, 2, 3))
        val o = outbox()
        assertTrue(o.oldest(10).isEmpty())
        assertTrue(o.recoveredFromCorruption)
        assertTrue(o.add(OutboxItem(event("fresh"), now)))
        assertEquals(listOf("fresh"), outbox().oldest(10).map { it.id })

        cipher.failDecrypt = true
        val lostKey = outbox()
        assertTrue(lostKey.oldest(10).isEmpty())
        assertTrue(lostKey.recoveredFromCorruption)
    }

    @Test fun `clear empties the queue and removes the file`() = runTest {
        val o = outbox()
        o.add(OutboxItem(event("a"), now)); o.add(OutboxItem(event("b"), now))
        assertEquals(2, o.clear())
        assertEquals(0, o.size.value)
        assertFalse(File(tmp.root, "q/outbox.bin").exists())
    }
}

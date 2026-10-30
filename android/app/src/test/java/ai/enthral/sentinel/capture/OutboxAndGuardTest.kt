package ai.enthral.sentinel.capture

import org.junit.Assert.assertEquals
import org.junit.Test

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

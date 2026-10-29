package ai.enthral.sentinel.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin redactor must give exactly the backend's output for every golden case. The golden file is produced by
 * the real Python redactor (android/scripts/gen-redact-golden.py); regenerate it whenever redact.py changes.
 * There are no intentional differences: any divergence is a bug in the port.
 */
class RedactorParityTest {
    @Serializable
    data class Case(val input: String, val output: String)

    private val cases: List<Case> = Json.decodeFromString(
        javaClass.getResourceAsStream("/redact_golden.json")!!.bufferedReader(Charsets.UTF_8).readText(),
    )

    @Test fun `golden file is large enough`() {
        assertTrue("only ${cases.size} cases", cases.size >= 300)
        assertTrue(cases.count { it.input != it.output } >= 100)
    }

    @Test fun `kotlin output equals python output for every golden case`() {
        val failures = cases.mapNotNull { c ->
            val got = Redactor.redact(c.input)
            if (got == c.output) null else "IN:  ${c.input.take(200)}\nPY:  ${c.output.take(200)}\nKT:  ${got.take(200)}"
        }
        assertEquals("${failures.size} divergences:\n" + failures.take(30).joinToString("\n\n"), 0, failures.size)
    }

    // Idempotence is not asserted separately: the generator adds every Python output as another golden input, so the
    // parity test already proves redact(redact(x)) matches Python (which is not always idempotent, e.g.
    // "OTP 482913 and 991234" -> "OTP [OTP] and 991234" -> "OTP [OTP] and [OTP]").

    @Test fun `spot checks`() {
        assertEquals("Your OTP is [OTP]. Do not share it with anyone. -HDFC Bank",
            Redactor.redact("Your OTP is 482913. Do not share it with anyone. -HDFC Bank"))
        assertFalse(Redactor.redact("आपका ओटीपी ४८२९१३ है").contains("४८२९१३"))
        assertEquals("pay ₹4,999 to ramesh@okhdfcbank call +91 98765 43210",
            Redactor.redact("pay ₹4,999 to ramesh@okhdfcbank call +91 98765 43210"))
    }
}

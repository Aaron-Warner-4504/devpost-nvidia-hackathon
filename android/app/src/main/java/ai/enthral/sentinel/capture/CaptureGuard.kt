package ai.enthral.sentinel.capture

import java.security.MessageDigest

fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/**
 * In-memory de-duplication and per-package rate limit. Holds only hashes and timestamps, never text.
 * - the same (package, conversation/notification key, text hash) within [dedupeWindowMs] is dropped
 *   (notification updates that do not change the text, re-posts, WhatsApp history replays);
 * - at most [maxPerWindow] events per package per [rateWindowMs] (sliding window).
 */
class CaptureGuard(
    private val clock: () -> Long,
    private val dedupeWindowMs: Long = 10 * 60_000L,
    private val rateWindowMs: Long = 10 * 60_000L,
    private val maxPerWindow: Int = 20,
) {
    enum class Verdict { Allow, Duplicate, RateLimited }

    private val seen = HashMap<String, Long>()
    private val perPackage = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun check(packageName: String, key: String, redactedText: String): Verdict {
        val now = clock()
        seen.entries.removeAll { now - it.value >= dedupeWindowMs }
        val fingerprint = sha256Hex("$packageName|$key|$redactedText")
        if (seen.containsKey(fingerprint)) return Verdict.Duplicate
        val window = perPackage.getOrPut(packageName) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= rateWindowMs) window.removeFirst()
        if (window.size >= maxPerWindow) return Verdict.RateLimited
        window.addLast(now)
        seen[fingerprint] = now
        return Verdict.Allow
    }

    /** Events accepted per package in the current window (numbers only, for Settings). */
    @Synchronized
    fun counts(): Map<String, Int> {
        val now = clock()
        return perPackage.mapValues { (_, w) -> w.count { now - it < rateWindowMs } }.filterValues { it > 0 }
    }
}

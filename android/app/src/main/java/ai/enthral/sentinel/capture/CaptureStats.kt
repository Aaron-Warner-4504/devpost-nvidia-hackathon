package ai.enthral.sentinel.capture

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** [Failed]: a notification that could not be processed (malformed extras, provider errors). Counted, never logged. */
enum class Counter { Captured, Sent, Dropped, RateLimited, Duplicate, Failed }

/** Numbers only: never content, senders or package-level detail beyond counts. */
data class StatsSnapshot(
    val counts: Map<Counter, Long> = emptyMap(),
    val lastCapturedAt: Long? = null,
    val lastSentAt: Long? = null,
) {
    operator fun get(c: Counter): Long = counts[c] ?: 0L
}

interface CaptureStats {
    val snapshot: Flow<StatsSnapshot>
    suspend fun add(counter: Counter, n: Long = 1)
    suspend fun markCaptured(at: Long)
    suspend fun markSent(at: Long)
    suspend fun clear()
}

/** Persisted MessagingStyle cursors: newest message timestamp seen per (package, conversation), keyed by a hash. */
interface CursorStore {
    suspend fun get(conversationKey: String): Long?
    suspend fun put(conversationKey: String, ts: Long)
    suspend fun clear()
}

/** Call capture state: the call-log cursor and recently screened (ringing) calls, keyed by a number hash. */
interface CallState {
    suspend fun callCursor(): Long?
    suspend fun setCallCursor(date: Long)

    /** sha256(E.164 number) -> time the call was screened (last 24 h only). */
    suspend fun screened(): Map<String, Long>
    suspend fun addScreened(number: String, at: Long)
}

/** One DataStore file for stats and cursors. Conversation keys are stored as SHA-256 (they can contain names). */
class DataStoreCaptureState(
    private val store: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) : CaptureStats, CursorStore, CallState {
    override val snapshot: Flow<StatsSnapshot> = store.data.map { p ->
        StatsSnapshot(
            counts = Counter.entries.associateWith { p[counterKey(it)] ?: 0L },
            lastCapturedAt = p[LAST_CAPTURED],
            lastSentAt = p[LAST_SENT],
        )
    }

    override suspend fun add(counter: Counter, n: Long) {
        store.edit { it[counterKey(counter)] = (it[counterKey(counter)] ?: 0L) + n }
    }

    override suspend fun markCaptured(at: Long) {
        store.edit { it[LAST_CAPTURED] = at }
    }

    override suspend fun markSent(at: Long) {
        store.edit { it[LAST_SENT] = at }
    }

    override suspend fun get(conversationKey: String): Long? = store.data.first()[cursorKey(conversationKey)]

    override suspend fun put(conversationKey: String, ts: Long) {
        store.edit { prefs ->
            val k = cursorKey(conversationKey)
            if ((prefs[k] ?: Long.MIN_VALUE) < ts) prefs[k] = ts
        }
    }

    override suspend fun callCursor(): Long? = store.data.first()[CALL_CURSOR]

    override suspend fun setCallCursor(date: Long) {
        store.edit { if ((it[CALL_CURSOR] ?: Long.MIN_VALUE) < date) it[CALL_CURSOR] = date }
    }

    override suspend fun screened(): Map<String, Long> = parseScreened(store.data.first()[SCREENED].orEmpty())

    override suspend fun addScreened(number: String, at: Long) {
        store.edit { prefs ->
            val now = clock()
            val kept = parseScreened(prefs[SCREENED].orEmpty()).filterValues { now - it < 24L * 3600 * 1000 }
            prefs[SCREENED] = (kept + (sha256Hex(number) to at)).map { (h, t) -> "$h|$t" }.toSet()
        }
    }

    private fun parseScreened(raw: Set<String>): Map<String, Long> = raw.mapNotNull { e ->
        val t = e.substringAfter('|', "").toLongOrNull() ?: return@mapNotNull null
        e.substringBefore('|') to t
    }.toMap()

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    private fun counterKey(c: Counter) = longPreferencesKey("count_${c.name.lowercase()}")
    private fun cursorKey(conversationKey: String) = longPreferencesKey("cursor_" + sha256Hex(conversationKey).take(32))

    private companion object {
        val LAST_CAPTURED = longPreferencesKey("last_captured_at")
        val LAST_SENT = longPreferencesKey("last_sent_at")
        val CALL_CURSOR = longPreferencesKey("call_log_cursor")
        val SCREENED = androidx.datastore.preferences.core.stringSetPreferencesKey("screened_calls")
    }
}

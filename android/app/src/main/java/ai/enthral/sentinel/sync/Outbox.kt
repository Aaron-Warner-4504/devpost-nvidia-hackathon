package ai.enthral.sentinel.sync

import ai.enthral.sentinel.data.api.EventIn
import ai.enthral.sentinel.data.api.SentinelJson
import ai.enthral.sentinel.data.store.TokenCipher
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One queued event. [event] is already redacted: raw text never reaches the outbox. */
@Serializable
data class OutboxItem(val event: EventIn, val enqueuedAt: Long, val attempts: Int = 0) {
    val id: String get() = event.deviceEventId
}

interface Outbox {
    val size: StateFlow<Int>

    /** Adds unless an item with the same device_event_id is queued. Returns false for a duplicate. */
    suspend fun add(item: OutboxItem): Boolean

    /** Oldest first (enqueue order), after expiring items older than the TTL. */
    suspend fun oldest(limit: Int): List<OutboxItem>
    suspend fun remove(id: String)
    suspend fun recordAttempt(id: String)
    suspend fun clear(): Int
}

/**
 * Encrypted-at-rest queue in one file: `cipher(JSON list)`, written atomically (temp file + rename). The whole
 * queue is small (max [maxItems], each <= ~10 KB) and lives in memory between writes.
 * - over [maxItems]: the oldest items are dropped (counted through [onDropped]);
 * - older than [ttlMs]: dropped on the next access;
 * - unreadable file (key lost, truncated, garbage): the queue restarts empty instead of crashing.
 */
class EncryptedFileOutbox(
    private val file: File,
    private val cipher: TokenCipher,
    private val clock: () -> Long,
    private val maxItems: Int = 500,
    private val ttlMs: Long = 7L * 24 * 3600 * 1000,
    private val onDropped: suspend (Int) -> Unit = {},
) : Outbox {
    private val lock = Mutex()
    private var items: MutableList<OutboxItem>? = null
    private val _size = MutableStateFlow(0)
    override val size: StateFlow<Int> = _size.asStateFlow()
    private val serializer = ListSerializer(OutboxItem.serializer())

    /** True when the last load found an unreadable file (for tests/diagnostics). */
    var recoveredFromCorruption = false
        private set

    private suspend fun loaded(): MutableList<OutboxItem> {
        items?.let { return it }
        val list = read()
        items = list
        expire(list)
        _size.value = list.size
        return list
    }

    private fun read(): MutableList<OutboxItem> {
        if (!file.exists()) return mutableListOf()
        return try {
            val plain = cipher.decrypt(file.readBytes()) ?: throw IOException("undecryptable")
            SentinelJson.decodeFromString(serializer, plain.toString(Charsets.UTF_8)).toMutableList()
        } catch (_: IOException) {
            corrupt()
        } catch (_: SerializationException) {
            corrupt()
        } catch (_: IllegalArgumentException) {
            corrupt()
        }
    }

    private fun corrupt(): MutableList<OutboxItem> {
        recoveredFromCorruption = true
        file.delete()
        return mutableListOf()
    }

    private suspend fun expire(list: MutableList<OutboxItem>): Boolean {
        val now = clock()
        val before = list.size
        list.removeAll { now - it.enqueuedAt > ttlMs }
        val dropped = before - list.size
        if (dropped > 0) onDropped(dropped)
        return dropped > 0
    }

    private fun write(list: List<OutboxItem>) {
        _size.value = list.size
        file.parentFile?.mkdirs()
        if (list.isEmpty()) {
            file.delete()
            return
        }
        val blob = cipher.encrypt(SentinelJson.encodeToString(serializer, list).toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("outbox rename failed")
        }
    }

    override suspend fun add(item: OutboxItem): Boolean = lock.withLock {
        val list = loaded()
        expire(list)
        if (list.any { it.id == item.id }) return false
        list += item
        val overflow = list.size - maxItems
        if (overflow > 0) {
            repeat(overflow) { list.removeAt(0) }
            onDropped(overflow)
        }
        write(list)
        true
    }

    override suspend fun oldest(limit: Int): List<OutboxItem> = lock.withLock {
        val list = loaded()
        if (expire(list)) write(list)
        list.take(limit)
    }

    override suspend fun remove(id: String) = lock.withLock {
        val list = loaded()
        if (list.removeAll { it.id == id }) write(list)
    }

    override suspend fun recordAttempt(id: String) = lock.withLock {
        val list = loaded()
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            list[i] = list[i].copy(attempts = list[i].attempts + 1)
            write(list)
        }
    }

    override suspend fun clear(): Int = lock.withLock {
        val list = loaded()
        val n = list.size
        list.clear()
        write(list)
        n
    }

    /** Loads the queue size eagerly (for the status card) without changing anything. */
    suspend fun warmUp() = lock.withLock { loaded(); Unit }
}

package ai.enthral.sentinel.data.db

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The one on-device database (events, assessments, feedback, sync outbox), encrypted with SQLCipher. The passphrase is
 * random per install and sealed by the Android Keystore ([DatabasePassphraseProvider]). Opened lazily on first use
 * because the passphrase read is suspend.
 *
 * If the database cannot be opened with the stored key (Keystore key lost after a restore, or a wipe interrupted
 * between deleting the key and the file), it is deleted and recreated empty instead of crashing: nothing in it is
 * worth more than a working app, and the server keeps the redacted history.
 */
class SentinelDatabase(
    private val context: Context,
    private val passphrases: DatabasePassphraseProvider,
    private val name: String = "sentinel.db",
) {
    private val lock = Mutex()
    private var db: AppDatabase? = null

    suspend fun get(): AppDatabase = lock.withLock { db ?: open().also { db = it } }

    private suspend fun open(): AppDatabase = withContext(Dispatchers.IO) {
        System.loadLibrary("sqlcipher")
        val key = passphrases.getOrCreatePassphrase()
        val first = build(key)
        try {
            first.openHelper.writableDatabase
            first
        } catch (_: RuntimeException) {
            first.close()
            context.deleteDatabase(name)
            build(key).also { it.openHelper.writableDatabase }
        }
    }

    // The factory may zero the array it is given after opening: always hand it a copy.
    private fun build(key: ByteArray): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf()))
            .build()

    /** Delete-my-data: close, delete the file and forget the key (a new one is made on next use). */
    suspend fun wipe() = lock.withLock {
        withContext(Dispatchers.IO) {
            db?.close()
            db = null
            context.deleteDatabase(name)
            passphrases.clear()
        }
    }
}

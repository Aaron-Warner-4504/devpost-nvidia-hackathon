package ai.enthral.sentinel.data.db

import ai.enthral.sentinel.data.store.TokenCipher
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.SecureRandom
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Derives and securely persists a 256-bit passphrase for SQLCipher,
 * protected by Android Keystore AES-GCM via [TokenCipher].
 */
class DatabasePassphraseProvider(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val encode: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) },
    private val decode: (String) -> ByteArray = { Base64.decode(it, Base64.NO_WRAP) },
) {
    suspend fun getOrCreatePassphrase(): ByteArray {
        val stored = dataStore.data.map { it[KEY] }.first()
        if (stored != null) {
            val decrypted = try {
                cipher.decrypt(decode(stored))
            } catch (_: Exception) {
                null
            }
            if (decrypted != null && decrypted.isNotEmpty()) {
                return decrypted
            }
        }

        // Generate new 32-byte (256-bit) cryptographically strong key
        val newKey = ByteArray(32)
        SecureRandom().nextBytes(newKey)
        val encrypted = encode(cipher.encrypt(newKey))
        dataStore.edit { it[KEY] = encrypted }
        return newKey
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("db_passphrase_enc")
    }
}

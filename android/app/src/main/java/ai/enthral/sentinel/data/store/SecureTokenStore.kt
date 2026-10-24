package ai.enthral.sentinel.data.store

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the device token encrypted at rest. Only ciphertext reaches DataStore; the token is never logged
 * (this class has no logging and its toString is the default object form).
 */
class SecureTokenStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    private val encode: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) },
    private val decode: (String) -> ByteArray = { Base64.decode(it, Base64.NO_WRAP) },
) {
    suspend fun token(): String? {
        val stored = dataStore.data.map { it[KEY] }.first() ?: return null
        val plain = try {
            cipher.decrypt(decode(stored))
        } catch (_: IllegalArgumentException) {
            null // corrupt base64
        }
        if (plain == null) {
            // Unreadable (e.g. keystore key lost after restore): drop it so the app re-registers cleanly.
            clear()
            return null
        }
        return String(plain, Charsets.UTF_8)
    }

    suspend fun save(token: String) {
        val blob = encode(cipher.encrypt(token.toByteArray(Charsets.UTF_8)))
        dataStore.edit { it[KEY] = blob }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("device_token_enc")
    }
}

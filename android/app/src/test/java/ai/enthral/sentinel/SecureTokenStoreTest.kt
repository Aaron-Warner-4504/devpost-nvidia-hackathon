package ai.enthral.sentinel

import ai.enthral.sentinel.data.store.SecureTokenStore
import ai.enthral.sentinel.data.store.TokenCipher
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecureTokenStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Not real crypto: reversible and obviously different from the plaintext. */
    private class FakeCipher(var broken: Boolean = false) : TokenCipher {
        override fun encrypt(plain: ByteArray) = "ENC".toByteArray() + plain.reversedArray()
        override fun decrypt(blob: ByteArray): ByteArray? =
            if (broken || blob.size < 3) null else blob.copyOfRange(3, blob.size).reversedArray()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var file: File
    private val cipher = FakeCipher()

    private fun store(f: File = file) = SecureTokenStore(
        PreferenceDataStoreFactory.create(scope = scope) { f },
        cipher,
        encode = { Base64.getEncoder().encodeToString(it) },
        decode = { Base64.getDecoder().decode(it) },
    )

    @Before fun setUp() { file = File(tmp.newFolder(), "secure.preferences_pb") }
    @After fun tearDown() = scope.cancel()

    @Test fun `no token initially`() = runTest { assertNull(store().token()) }

    @Test fun `save then read round-trips`() = runTest {
        val s = store()
        s.save("tok-secret-123")
        assertEquals("tok-secret-123", s.token())
    }

    @Test fun `plaintext never reaches the datastore file`() = runTest {
        val s = store()
        s.save("tok-secret-123")
        s.token()
        val bytes = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(bytes.contains("tok-secret-123"))
        assertFalse(bytes.contains("123-terces-kot")) // fake cipher's reversed form is also base64-wrapped
    }

    @Test fun `unreadable blob yields null and is cleared`() = runTest {
        val s = store()
        s.save("tok-secret-123")
        cipher.broken = true
        assertNull(s.token())
        cipher.broken = false
        assertNull("a cleared token must not come back", s.token())
    }

    @Test fun `clear removes the token`() = runTest {
        val s = store()
        s.save("abc")
        s.clear()
        assertNull(s.token())
    }

    @Test fun `overwrite keeps only the newest token`() = runTest {
        val s = store()
        s.save("one")
        s.save("two")
        assertEquals("two", s.token())
    }
}

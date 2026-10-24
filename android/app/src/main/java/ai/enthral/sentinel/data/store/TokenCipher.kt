package ai.enthral.sentinel.data.store

/** Seals/opens bytes. Abstracted so the store logic is unit-testable without an Android Keystore. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** Returns null when the blob cannot be opened (key invalidated, data corrupt). */
    fun decrypt(blob: ByteArray): ByteArray?
}

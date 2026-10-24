package ai.enthral.sentinel.data.repo

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.store.SecureTokenStore
import java.security.GeneralSecurityException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns "is this install registered": registers once, stores the token encrypted, can reset after a 401. */
interface DeviceSession {
    suspend fun isRegistered(): Boolean

    /** Registers if needed. Only the onboarding finish step (after consent) calls this directly. */
    suspend fun ensureRegistered(): ApiResult<Unit>

    /** Drops the stored token (e.g. delete-my-data, or the server rejected it). */
    suspend fun reset()

    /**
     * Runs an authenticated call. A 401 means the token is stale: re-register once and retry once.
     * Never registers on its own for an install that has not finished onboarding (the caller guarantees that).
     */
    suspend fun <T> authed(block: suspend () -> ApiResult<T>): ApiResult<T>
}

class ApiDeviceSession(private val api: SentinelApi, private val tokens: SecureTokenStore) : DeviceSession {
    private val lock = Mutex()

    override suspend fun isRegistered(): Boolean = safeToken() != null

    override suspend fun ensureRegistered(): ApiResult<Unit> = lock.withLock {
        if (safeToken() != null) return ApiResult.Success(Unit)
        when (val r = api.register()) {
            is ApiResult.Success -> try {
                tokens.save(r.value.token)
                ApiResult.Success(Unit)
            } catch (_: GeneralSecurityException) {
                ApiResult.NetworkError("TokenStorageUnavailable")
            }
            is ApiResult.HttpError -> r
            is ApiResult.NetworkError -> r
        }
    }

    override suspend fun reset() = lock.withLock { tokens.clear() }

    override suspend fun <T> authed(block: suspend () -> ApiResult<T>): ApiResult<T> {
        when (val reg = ensureRegistered()) {
            is ApiResult.HttpError -> return reg
            is ApiResult.NetworkError -> return reg
            is ApiResult.Success -> Unit
        }
        val first = block()
        if (first !is ApiResult.HttpError || !first.isUnauthorized) return first
        reset()
        when (val reg = ensureRegistered()) {
            is ApiResult.HttpError -> return reg
            is ApiResult.NetworkError -> return reg
            is ApiResult.Success -> Unit
        }
        return block()
    }

    private suspend fun safeToken(): String? = try {
        tokens.token()
    } catch (_: GeneralSecurityException) {
        null
    }
}

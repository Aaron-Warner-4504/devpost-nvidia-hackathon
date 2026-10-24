package ai.enthral.sentinel.data.repo

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.store.SecureTokenStore
import java.security.GeneralSecurityException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns "is this install registered": registers once, stores the token encrypted, can reset after a 401. */
class DeviceSession(private val api: SentinelApi, private val tokens: SecureTokenStore) {
    private val lock = Mutex()

    suspend fun ensureRegistered(): ApiResult<Unit> = lock.withLock {
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

    /** Drops the stored token (e.g. after the server answered 401) so the next call registers again. */
    suspend fun reset() = lock.withLock { tokens.clear() }

    private suspend fun safeToken(): String? = try {
        tokens.token()
    } catch (_: GeneralSecurityException) {
        null
    }
}

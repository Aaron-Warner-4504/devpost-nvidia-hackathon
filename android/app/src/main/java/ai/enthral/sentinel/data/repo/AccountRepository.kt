package ai.enthral.sentinel.data.repo

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.store.AppSettings

interface AccountRepository {
    /**
     * Delete-my-data: purge the sync outbox first (works offline: nothing queued may leave the phone after the user
     * asked to delete), then DELETE /v1/me, then wipe local state. A 401/404 means the server already forgot us, so
     * the local wipe still happens. A network failure keeps the account, so the user can simply retry.
     */
    suspend fun deleteEverything(): ApiResult<Unit>
}

class NetworkAccountRepository(
    private val api: SentinelApi,
    private val session: DeviceSession,
    private val settings: AppSettings,
    private val alerts: AlertsRepository,
    /** Wipes on-device capture state (outbox, counters, pseudonym key, scheduled work). */
    private val onWipe: suspend () -> Unit = {},
    /** Drops every queued event and cancels sync work; runs before the network call. */
    private val onPurgeQueued: suspend () -> Unit = {},
) : AccountRepository {
    override suspend fun deleteEverything(): ApiResult<Unit> {
        onPurgeQueued()
        if (session.isRegistered()) {
            when (val r = api.deleteMe()) {
                is ApiResult.Success -> Unit
                is ApiResult.HttpError -> if (r.status != 401 && r.status != 404) return r
                is ApiResult.NetworkError -> return r
            }
        }
        session.reset()
        onWipe()
        alerts.clear()
        settings.clearAll()
        return ApiResult.Success(Unit)
    }
}

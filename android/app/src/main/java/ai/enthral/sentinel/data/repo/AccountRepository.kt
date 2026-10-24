package ai.enthral.sentinel.data.repo

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.store.AppSettings

interface AccountRepository {
    /**
     * Delete-my-data: DELETE /v1/me, then wipe local state. A 401/404 means the server already forgot us, so the
     * local wipe still happens. A network failure changes nothing, so the user can simply retry.
     */
    suspend fun deleteEverything(): ApiResult<Unit>
}

class NetworkAccountRepository(
    private val api: SentinelApi,
    private val session: DeviceSession,
    private val settings: AppSettings,
    private val alerts: AlertsRepository,
) : AccountRepository {
    override suspend fun deleteEverything(): ApiResult<Unit> {
        if (session.isRegistered()) {
            when (val r = api.deleteMe()) {
                is ApiResult.Success -> Unit
                is ApiResult.HttpError -> if (r.status != 401 && r.status != 404) return r
                is ApiResult.NetworkError -> return r
            }
        }
        session.reset()
        alerts.clear()
        settings.clearAll()
        return ApiResult.Success(Unit)
    }
}

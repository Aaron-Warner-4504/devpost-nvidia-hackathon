package ai.enthral.sentinel.data.repo

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.api.map
import ai.enthral.sentinel.data.api.toDomain
import ai.enthral.sentinel.domain.Alert

class AlertsRepository(private val api: SentinelApi, private val session: DeviceSession) {

    /** Recent alerts, newest first. Registers on first use; re-registers once if the server rejects the token. */
    suspend fun recent(): ApiResult<List<Alert>> {
        val first = session.ensureRegistered()
        if (first !is ApiResult.Success) return first.map { emptyList() }
        var result = api.getAlerts()
        if (result is ApiResult.HttpError && result.isUnauthorized) {
            session.reset()
            val re = session.ensureRegistered()
            if (re !is ApiResult.Success) return re.map { emptyList() }
            result = api.getAlerts()
        }
        return result.map { list -> list.map { it.toDomain() } }
    }
}

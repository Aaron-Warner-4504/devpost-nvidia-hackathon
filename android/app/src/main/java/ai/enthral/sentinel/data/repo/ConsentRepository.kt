package ai.enthral.sentinel.data.repo

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.domain.ConsentSource

interface ConsentRepository {
    /** Records grant/revoke on the server for every backend source behind [source]. */
    suspend fun sync(source: ConsentSource, granted: Boolean): ApiResult<Unit>
}

class NetworkConsentRepository(private val api: SentinelApi, private val session: DeviceSession) : ConsentRepository {
    override suspend fun sync(source: ConsentSource, granted: Boolean): ApiResult<Unit> {
        for (s in source.backendSources) {
            when (val r = session.authed { api.setConsent(s, granted) }) {
                is ApiResult.Success -> Unit
                is ApiResult.HttpError -> return r
                is ApiResult.NetworkError -> return r
            }
        }
        return ApiResult.Success(Unit)
    }
}

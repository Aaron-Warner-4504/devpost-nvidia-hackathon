package ai.enthral.sentinel.data.repo

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.EventIn
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.api.toDomain
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.Source
import java.time.Instant
import java.util.UUID

/** "Check this": the user explicitly sends content for a full analysis. Always yields an alert on success. */
interface CheckRepository {
    suspend fun checkText(text: String, sender: String?, lang: String): ApiResult<Alert>
    suspend fun checkImage(bytes: ByteArray, mimeType: String, lang: String): ApiResult<Alert>
}

class NetworkCheckRepository(
    private val api: SentinelApi,
    private val session: DeviceSession,
    private val alerts: AlertsRepository,
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { "chk-" + UUID.randomUUID() },
) : CheckRepository {

    override suspend fun checkText(text: String, sender: String?, lang: String): ApiResult<Alert> {
        val event = EventIn(
            deviceEventId = newId(),
            source = Source.Share,
            ts = clock().toString(),
            sender = sender?.trim()?.ifEmpty { null },
            text = text,
            triageScore = 0.5,
            lang = lang,
        )
        return finish(session.authed { api.check(event) })
    }

    override suspend fun checkImage(bytes: ByteArray, mimeType: String, lang: String): ApiResult<Alert> {
        val id = newId()
        val now = clock().toString()
        return finish(session.authed {
            api.checkImage(bytes, mimeType, deviceEventId = id, ts = now, lang = lang, triageScore = 0.5)
        })
    }

    private suspend fun finish(result: ApiResult<ai.enthral.sentinel.data.api.EventResult>): ApiResult<Alert> =
        when (result) {
            is ApiResult.Success -> {
                val alert = result.value.alert?.toDomain()
                if (alert == null) {
                    ApiResult.HttpError(200, "check returned no alert")
                } else {
                    alerts.remember(alert)
                    ApiResult.Success(alert)
                }
            }
            is ApiResult.HttpError -> result
            is ApiResult.NetworkError -> result
        }
}

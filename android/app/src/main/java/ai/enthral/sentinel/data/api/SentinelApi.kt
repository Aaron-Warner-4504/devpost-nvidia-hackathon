package ai.enthral.sentinel.data.api

import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.domain.Source

/** Typed surface of docs/api-contract.md. Every call returns an [ApiResult]; none throws. */
interface SentinelApi {
    suspend fun register(): ApiResult<DeviceRegistered>
    suspend fun registerFcmToken(token: String): ApiResult<Ok>

    suspend fun postEvent(event: EventIn): ApiResult<EventResult>
    suspend fun check(event: EventIn): ApiResult<EventResult>

    /** Screenshot check (multipart). Optional fields fall back to server defaults when null. */
    suspend fun checkImage(
        image: ByteArray,
        mimeType: String,
        deviceEventId: String? = null,
        ts: String? = null,
        lang: String? = null,
        triageScore: Double? = null,
    ): ApiResult<EventResult>

    /** [since] is an ISO-8601 instant; newest first, max 100. */
    suspend fun getAlerts(since: String? = null): ApiResult<List<AlertOut>>
    suspend fun postFeedback(alertId: String, label: FeedbackLabel): ApiResult<Ok>

    suspend fun createFamilyInvite(consent: Boolean): ApiResult<FamilyInviteOut>
    suspend fun acceptFamilyInvite(code: String, name: String, fcmToken: String): ApiResult<FamilyLinkOut>
    suspend fun getFamilyContacts(): ApiResult<FamilyContacts>
    suspend fun unlinkFamily(linkId: String): ApiResult<Unit>

    suspend fun getMe(): ApiResult<Me>
    suspend fun setConsent(source: Source, granted: Boolean): ApiResult<ConsentOut>
    suspend fun deleteMe(): ApiResult<Unit>
}

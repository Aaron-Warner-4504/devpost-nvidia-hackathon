package ai.enthral.sentinel.data.api

import ai.enthral.sentinel.domain.CallDirection
import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.domain.Source
import ai.enthral.sentinel.domain.Tier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Field names and types mirror docs/api-contract.md exactly. Timestamps stay ISO-8601 strings on the wire
// (round-trips unchanged); data/api/Mappers.kt converts them to java.time for the domain layer.

@Serializable
data class DeviceRegistered(
    @SerialName("device_id") val deviceId: String,
    val token: String,
)

@Serializable
data class CallMeta(
    val number: String,
    @SerialName("duration_s") val durationS: Int,
    @SerialName("is_contact") val isContact: Boolean,
    val direction: CallDirection,
)

@Serializable
data class EventIn(
    @SerialName("device_event_id") val deviceEventId: String,
    val source: Source,
    val ts: String,
    val sender: String? = null,
    val text: String? = null,
    val call: CallMeta? = null,
    @SerialName("triage_score") val triageScore: Double,
    val lang: String? = null,
    @SerialName("is_contact") val isContact: Boolean? = null,
)

@Serializable
data class WhyItemDto(
    @SerialName("event_id") val eventId: String,
    val ts: String,
    val text: String,
)

@Serializable
data class AlertOut(
    val id: String,
    @SerialName("event_id") val eventId: String,
    val tier: Tier,
    val headline: String,
    val why: List<WhyItemDto>,
    @SerialName("next_step") val nextStep: String,
    val signals: List<String>,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class EventResult(
    @SerialName("event_id") val eventId: String,
    val duplicate: Boolean,
    val alert: AlertOut? = null,
)

@Serializable
data class FeedbackIn(val label: FeedbackLabel)

@Serializable
data class FcmIn(val token: String)

@Serializable
data class Ok(val ok: Boolean)

@Serializable
data class ConsentIn(val source: Source, val granted: Boolean)

@Serializable
data class ConsentOut(val source: Source, val granted: Boolean)

/** GET /v1/me. `consents` maps source wire name to granted (not in api-contract.md; taken from backend/app/routes/me.py). */
@Serializable
data class Me(val id: String, val consents: Map<String, Boolean> = emptyMap())

@Serializable
data class FamilyInviteIn(val consent: Boolean)

@Serializable
data class FamilyInviteOut(
    val code: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class FamilyAcceptIn(
    val code: String,
    val name: String,
    @SerialName("fcm_token") val fcmToken: String,
)

@Serializable
data class FamilyLinkOut(
    val id: String,
    @SerialName("protected_name") val protectedName: String? = null,
)

@Serializable
data class GuardianOut(
    val id: String,
    val name: String,
    @SerialName("linked_at") val linkedAt: String,
)

@Serializable
data class ProtectingOut(
    val id: String,
    @SerialName("linked_at") val linkedAt: String,
)

@Serializable
data class FamilyContacts(
    val guardians: List<GuardianOut>,
    val protecting: List<ProtectingOut>,
)

/** FastAPI error body: `detail` is a string, or a list of validation objects on 422. */
@Serializable
internal data class ErrorBody(val detail: kotlinx.serialization.json.JsonElement? = null)

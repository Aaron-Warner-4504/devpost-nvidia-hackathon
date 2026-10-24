package ai.enthral.sentinel.domain

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Alert severity. Wire names are the lower-case strings of docs/api-contract.md. */
@Serializable
enum class Tier {
    @SerialName("info") Info,
    @SerialName("caution") Caution,
    @SerialName("high") High,
    @SerialName("critical") Critical;

    /** Only high and critical interrupt the user (heads-up notification). */
    val interrupts: Boolean get() = this == High || this == Critical
}

/** Where an event came from; also the consent unit. Wire names per the contract. */
@Serializable
enum class Source {
    @SerialName("sms") Sms,
    @SerialName("whatsapp") Whatsapp,
    @SerialName("email") Email,
    @SerialName("call") Call,
    @SerialName("share") Share,
    @SerialName("notification") Notification,
}

@Serializable
enum class CallDirection {
    @SerialName("in") In,
    @SerialName("out") Out,
    @SerialName("missed") Missed,
}

@Serializable
enum class FeedbackLabel {
    @SerialName("scam") Scam,
    @SerialName("safe") Safe,
}

/** One step of the "why now" timeline. */
data class WhyItem(val eventId: String, val timestamp: Instant, val text: String)

data class Alert(
    val id: String,
    val eventId: String,
    val tier: Tier,
    val headline: String,
    val why: List<WhyItem>,
    val nextStep: String,
    val signals: List<String>,
    val createdAt: Instant,
)

/** What the user consents to. One app-level source maps to one or more backend `Source` consent records. */
enum class ConsentSource(val backendSources: List<Source>) {
    /** Notification listener: SMS, WhatsApp, bank apps. */
    Notifications(listOf(Source.Notification, Source.Sms, Source.Whatsapp)),

    /** Call number, time, duration, direction. Never audio. */
    Calls(listOf(Source.Call)),

    /** "Check this": always on, the user sends content explicitly. */
    Check(listOf(Source.Share)),
}

enum class SourceStatus { Off, NeedsPermission, On }

enum class ProtectionStatus { SetupIncomplete, Protected, Limited, Paused, Offline }

/** Pure rule for the Home hero. Only Notifications and Calls are monitored sources. */
fun computeProtection(
    notifications: SourceStatus,
    calls: SourceStatus,
    paused: Boolean,
    offline: Boolean,
): ProtectionStatus {
    val monitored = listOf(notifications, calls)
    return when {
        monitored.none { it == SourceStatus.On } -> ProtectionStatus.SetupIncomplete
        paused -> ProtectionStatus.Paused
        monitored.any { it == SourceStatus.NeedsPermission } -> ProtectionStatus.Limited
        offline -> ProtectionStatus.Offline
        else -> ProtectionStatus.Protected
    }
}

data class Invite(val code: String, val expiresAt: Instant)
data class Guardian(val id: String, val name: String, val linkedAt: Instant)
data class Protectee(val id: String, val linkedAt: Instant)
data class FamilyLinks(val guardians: List<Guardian>, val protecting: List<Protectee>)

/** Content handed to the app through the system share sheet. */
data class SharedContent(val text: String?, val imageUri: String?)

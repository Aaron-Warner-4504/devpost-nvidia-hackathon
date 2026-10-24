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

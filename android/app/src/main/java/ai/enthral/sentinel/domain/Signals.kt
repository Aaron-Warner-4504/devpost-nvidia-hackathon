package ai.enthral.sentinel.domain

import androidx.annotation.StringRes
import ai.enthral.sentinel.R

/**
 * Plain-language labels for every signal the backend can emit (SIGNAL_WEIGHTS in
 * backend/app/pipeline/signals.py). SignalsTest keeps this map, a checked-in list and the backend file in sync.
 */
object Signals {
    val labels: Map<String, Int> = mapOf(
        "urgency" to R.string.signal_urgency,
        "authority_impersonation" to R.string.signal_authority_impersonation,
        "payment_request" to R.string.signal_payment_request,
        "credential_or_otp_ask" to R.string.signal_credential_or_otp_ask,
        "remote_access_ask" to R.string.signal_remote_access_ask,
        "new_domain" to R.string.signal_new_domain,
        "lookalike_domain" to R.string.signal_lookalike_domain,
        "unknown_sender" to R.string.signal_unknown_sender,
        "channel_hop" to R.string.signal_channel_hop,
        "repeat_contact" to R.string.signal_repeat_contact,
        "secrecy_demand" to R.string.signal_secrecy_demand,
        "amount_escalation" to R.string.signal_amount_escalation,
        "known_benign_sender" to R.string.signal_known_benign_sender,
        "injection_attempt" to R.string.signal_injection_attempt,
        "known_bad_entity" to R.string.signal_known_bad_entity,
        "user_marked_safe" to R.string.signal_user_marked_safe,
        "credential_form_on_new_domain" to R.string.signal_credential_form_on_new_domain,
        "apk_download" to R.string.signal_apk_download,
        "threat_feed_hit" to R.string.signal_threat_feed_hit,
    )

    @StringRes
    fun labelRes(signal: String): Int? = labels[signal]

    /** "some_new_signal" -> "Some new signal" for names this app version does not know yet. */
    fun humanise(signal: String): String =
        signal.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
}

package ai.enthral.sentinel.data.notify

import ai.enthral.sentinel.domain.Alert

/** Surfaces a high/critical alert as a heads-up notification. Implemented with the platform in AndroidAlertNotifier. */
interface AlertNotifier {
    fun ensureChannels()

    /** False when the user denied notifications (the app keeps working). */
    fun canNotify(): Boolean

    fun notify(alert: Alert)

    /** A high/critical answer about a call that is ringing right now ("Possible scam caller"). */
    fun notifyIncomingCall(alert: Alert) = notify(alert)
}

package ai.enthral.sentinel.capture

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import ai.enthral.sentinel.SentinelApp

/**
 * Opt-in (Settings -> "Check callers while ringing", ROLE_CALL_SCREENING). Every call is ALLOWED immediately with
 * an empty response: Sentinel never blocks, rejects, silences or hides a call. For incoming calls it then hands the
 * number to an expedited job that posts a call event (number, time, saved-contact yes/no; no audio) and, if the server
 * answers high/critical while the phone is still ringing, shows "Possible scam caller".
 */
class SentinelCallScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        try {
            respondToCall(details, CallResponse.Builder().build())
        } catch (_: RuntimeException) {
            return // the framework lets the call through anyway
        }
        try {
            if (Build.VERSION.SDK_INT >= 29 && details.callDirection != Call.Details.DIRECTION_INCOMING) return
            val number = details.handle?.schemeSpecificPart
            (applicationContext as SentinelApp).container.capture.onIncomingCall(number, System.currentTimeMillis())
        } catch (_: RuntimeException) {
            // never let a capture problem affect the call
        }
    }
}

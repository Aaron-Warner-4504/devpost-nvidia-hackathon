package ai.enthral.sentinel.capture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import ai.enthral.sentinel.SentinelApp

/** CallLog.Calls reader: NUMBER, DATE, DURATION, TYPE only (never CACHED_NAME or other name columns). */
class AndroidCallLogSource(private val context: Context) : CallLogSource {
    override fun rowsAfter(after: Long, limit: Int): List<CallRow> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val cols = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE)
        val out = ArrayList<CallRow>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, cols, "${CallLog.Calls.DATE} > ?", arrayOf(after.toString()),
                "${CallLog.Calls.DATE} ASC",
            )?.use { c ->
                while (out.size < limit && c.moveToNext()) {
                    out += CallRow(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getInt(4))
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        }
        return out
    }
}

/** E.164 with the SIM country (IN fallback): platform formatter first, then the Indian rules. */
class E164Formatter(context: Context) {
    private val region: String = run {
        val tm = context.getSystemService(TelephonyManager::class.java)
        (tm?.simCountryIso?.takeIf { it.length == 2 } ?: tm?.networkCountryIso?.takeIf { it.length == 2 } ?: "in").uppercase()
    }

    fun format(number: String): String? =
        PhoneNumberUtils.formatNumberToE164(number, region)
            ?: PhoneNormalizer.toE164(number, region)
            ?: if (region != "IN") PhoneNormalizer.toE164(number, "IN") else null
}

/**
 * PHONE_STATE: when a call ends (state IDLE), read the new call-log rows 5 s later (the dialer writes the row
 * after the call). Needs READ_PHONE_STATE; the 15-minute worker catches up if this broadcast is missed.
 */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_IDLE) return
        (context.applicationContext as SentinelApp).container.capture.scheduler.scheduleCallLogSync(5_000L)
    }
}

package ai.enthral.sentinel.capture

import android.util.Log

/**
 * Debug builds: one line per capture step under tag `SentinelCapture`, for android/scripts/watch-capture.sh.
 * Callers pass ONLY fixed words, enum names and numbers (never text, titles, senders, numbers or tokens).
 */
internal object CaptureTrace {
    const val TAG = "SentinelCapture"
    fun log(event: String, vararg fields: Pair<String, Any?>) {
        Log.i(TAG, (listOf(event) + fields.map { (k, v) -> "$k=$v" }).joinToString(" "))
    }
}

package ai.enthral.sentinel.capture

import ai.enthral.sentinel.domain.Source

/** Debug only: lets `adb shell cmd notification post` inject test notifications (they come from com.android.shell). */
internal object DebugCaptureExtras {
    val packages: Map<String, Source> = mapOf("com.android.shell" to Source.Sms)
}

package ai.enthral.sentinel.data.system

import ai.enthral.sentinel.data.store.AppSettings

/** Source of this device's push token. A placeholder until Firebase is wired. */
interface FcmTokenProvider {
    suspend fun token(): String
}

/** Stable per-install stand-in so family pairing works end to end; it cannot receive pushes. */
class PlaceholderFcmTokenProvider(private val settings: AppSettings) : FcmTokenProvider {
    override suspend fun token(): String = "placeholder-fcm-" + settings.installId()
}

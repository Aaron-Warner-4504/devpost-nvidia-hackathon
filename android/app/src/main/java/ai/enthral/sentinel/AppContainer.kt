package ai.enthral.sentinel

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import ai.enthral.sentinel.data.api.HttpClientFactory
import ai.enthral.sentinel.data.api.OkHttpSentinelApi
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.repo.AlertsRepository
import ai.enthral.sentinel.data.repo.DeviceSession
import ai.enthral.sentinel.data.store.KeystoreTokenCipher
import ai.enthral.sentinel.data.store.SecureTokenStore
import ai.enthral.sentinel.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-written DI: one place that wires the object graph. Everything is lazy so cold start stays cheap. */
class AppContainer(private val context: Context) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: SettingsStore by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = ioScope) { context.preferencesDataStoreFile("settings") })
    }

    val tokenStore: SecureTokenStore by lazy {
        SecureTokenStore(
            PreferenceDataStoreFactory.create(scope = ioScope) { context.preferencesDataStoreFile("secure") },
            KeystoreTokenCipher(),
        )
    }

    private val httpClient by lazy { HttpClientFactory.create(enableDebugLogging = BuildConfig.DEBUG) }

    val api: SentinelApi by lazy {
        OkHttpSentinelApi(BuildConfig.API_BASE_URL, httpClient, tokenProvider = { safeToken() })
    }

    val session: DeviceSession by lazy { DeviceSession(api, tokenStore) }
    val alerts: AlertsRepository by lazy { AlertsRepository(api, session) }

    private suspend fun safeToken(): String? = try {
        tokenStore.token()
    } catch (_: java.security.GeneralSecurityException) {
        null
    }
}

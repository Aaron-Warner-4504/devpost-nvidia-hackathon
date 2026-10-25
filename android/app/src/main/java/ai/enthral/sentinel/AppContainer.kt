package ai.enthral.sentinel

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import ai.enthral.sentinel.data.api.HttpClientFactory
import ai.enthral.sentinel.data.api.OkHttpSentinelApi
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.demo.DemoRepository
import ai.enthral.sentinel.data.demo.NetworkDemoRepository
import ai.enthral.sentinel.data.image.AndroidImagePreparer
import ai.enthral.sentinel.data.image.ImagePreparer
import ai.enthral.sentinel.data.notify.AlertNotifier
import ai.enthral.sentinel.data.notify.AndroidAlertNotifier
import ai.enthral.sentinel.data.repo.AccountRepository
import ai.enthral.sentinel.data.repo.AlertsRepository
import ai.enthral.sentinel.data.repo.ApiDeviceSession
import ai.enthral.sentinel.data.repo.CheckRepository
import ai.enthral.sentinel.data.repo.ConsentRepository
import ai.enthral.sentinel.data.repo.DeviceSession
import ai.enthral.sentinel.data.repo.FamilyRepository
import ai.enthral.sentinel.data.repo.NetworkAccountRepository
import ai.enthral.sentinel.data.repo.NetworkAlertsRepository
import ai.enthral.sentinel.data.repo.NetworkCheckRepository
import ai.enthral.sentinel.data.repo.NetworkConsentRepository
import ai.enthral.sentinel.data.repo.NetworkFamilyRepository
import ai.enthral.sentinel.data.store.AppSettings
import ai.enthral.sentinel.data.store.KeystoreTokenCipher
import ai.enthral.sentinel.data.store.SecureTokenStore
import ai.enthral.sentinel.data.store.SettingsStore
import ai.enthral.sentinel.data.system.AndroidSystemAccess
import ai.enthral.sentinel.data.system.AppCompatLocaleController
import ai.enthral.sentinel.data.system.FcmTokenProvider
import ai.enthral.sentinel.data.system.LocaleController
import ai.enthral.sentinel.data.system.PlaceholderFcmTokenProvider
import ai.enthral.sentinel.data.system.ShareIntake
import ai.enthral.sentinel.data.system.SystemAccess
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-written DI: the one place that wires the production object graph. Everything is lazy. */
class AppContainer(private val context: Context) : AppGraph {
    init {
        // An unconfigured release build carries the ".invalid" placeholder: fail loudly instead of calling a dummy host.
        check(!BuildConfig.API_BASE_URL.substringAfter("://").substringBefore('/').endsWith(".invalid")) {
            "Sentinel release build has no API URL. Rebuild with -PsentinelApiBaseUrl=https://your.host/path"
        }
    }

    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val settings: AppSettings by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = ioScope) { context.preferencesDataStoreFile("settings") })
    }

    private val tokenStore: SecureTokenStore by lazy {
        SecureTokenStore(
            PreferenceDataStoreFactory.create(scope = ioScope) { context.preferencesDataStoreFile("secure") },
            KeystoreTokenCipher(),
        )
    }

    private val httpClient by lazy { HttpClientFactory.create(enableDebugLogging = BuildConfig.DEBUG) }

    private val api: SentinelApi by lazy {
        OkHttpSentinelApi(BuildConfig.API_BASE_URL, httpClient, tokenProvider = { safeToken() })
    }

    override val session: DeviceSession by lazy { ApiDeviceSession(api, tokenStore) }
    override val notifier: AlertNotifier by lazy { AndroidAlertNotifier(context).also { it.ensureChannels() } }
    override val alerts: AlertsRepository by lazy { NetworkAlertsRepository(api, session, settings, notifier) }
    override val checks: CheckRepository by lazy { NetworkCheckRepository(api, session, alerts) }
    override val consent: ConsentRepository by lazy { NetworkConsentRepository(api, session) }
    override val family: FamilyRepository by lazy { NetworkFamilyRepository(api, session) }
    override val account: AccountRepository by lazy { NetworkAccountRepository(api, session, settings, alerts) }
    override val demo: DemoRepository by lazy { NetworkDemoRepository(api, session, alerts) }
    override val locale: LocaleController by lazy { AppCompatLocaleController() }
    override val access: SystemAccess by lazy { AndroidSystemAccess(context) }
    override val images: ImagePreparer by lazy { AndroidImagePreparer(context) }
    override val shareIntake = ShareIntake()
    override val fcm: FcmTokenProvider by lazy { PlaceholderFcmTokenProvider(settings) }
    override val clock: () -> Instant = Instant::now
    override val demoEnabled: Boolean = BuildConfig.DEMO_ENABLED
    override val debugInfo: DebugInfo? =
        if (BuildConfig.DEBUG) DebugInfo(BuildConfig.API_BASE_URL, BuildConfig.BUILD_TYPE) else null

    private suspend fun safeToken(): String? = try {
        tokenStore.token()
    } catch (_: java.security.GeneralSecurityException) {
        null
    }
}

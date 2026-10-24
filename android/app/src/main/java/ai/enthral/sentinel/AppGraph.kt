package ai.enthral.sentinel

import ai.enthral.sentinel.data.demo.DemoRepository
import ai.enthral.sentinel.data.image.ImagePreparer
import ai.enthral.sentinel.data.notify.AlertNotifier
import ai.enthral.sentinel.data.repo.AccountRepository
import ai.enthral.sentinel.data.repo.AlertsRepository
import ai.enthral.sentinel.data.repo.CheckRepository
import ai.enthral.sentinel.data.repo.ConsentRepository
import ai.enthral.sentinel.data.repo.DeviceSession
import ai.enthral.sentinel.data.repo.FamilyRepository
import ai.enthral.sentinel.data.store.AppSettings
import ai.enthral.sentinel.data.system.FcmTokenProvider
import ai.enthral.sentinel.data.system.LocaleController
import ai.enthral.sentinel.data.system.ShareIntake
import ai.enthral.sentinel.data.system.SystemAccess
import java.time.Instant
import kotlinx.coroutines.CoroutineScope

/** Everything a screen may depend on. The app wires real implementations; tests wire fakes. */
interface AppGraph {
    val settings: AppSettings
    val session: DeviceSession
    val alerts: AlertsRepository
    val checks: CheckRepository
    val consent: ConsentRepository
    val family: FamilyRepository
    val account: AccountRepository
    val demo: DemoRepository
    val locale: LocaleController
    val access: SystemAccess
    val notifier: AlertNotifier
    val images: ImagePreparer
    val shareIntake: ShareIntake
    val fcm: FcmTokenProvider

    /** Outlives screens: used to finish work (e.g. a pending feedback) after the user navigates away. */
    val appScope: CoroutineScope
    val clock: () -> Instant
    val demoEnabled: Boolean
    val debugInfo: DebugInfo?
}

/** Shown in Settings > About, debug builds only. */
data class DebugInfo(val baseUrl: String, val buildType: String)

/** Whether to offer the in-app demo (a BuildConfig flag: on in debug, off in release). */
val LocalDemoEnabled = androidx.compose.runtime.staticCompositionLocalOf { false }

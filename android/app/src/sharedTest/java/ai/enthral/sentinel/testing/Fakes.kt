package ai.enthral.sentinel.testing

import android.graphics.Bitmap
import ai.enthral.sentinel.AppGraph
import ai.enthral.sentinel.DebugInfo
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.demo.DemoRepository
import ai.enthral.sentinel.data.demo.DemoStep
import ai.enthral.sentinel.data.image.ImagePreparer
import ai.enthral.sentinel.data.image.PreparedImage
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
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.FamilyLinks
import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.domain.Invite
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.domain.WhyItem
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

val NetworkDown = ApiResult.NetworkError("UnknownHostException")
fun httpError(status: Int, detail: String? = null) = ApiResult.HttpError(status, detail)

object Samples {
    val now: Instant = Instant.parse("2026-10-24T12:00:00Z")

    fun alert(
        id: String = "alr_1",
        tier: Tier = Tier.Critical,
        createdAt: Instant = now,
        eventId: String = "evt_$id",
        signals: List<String> = listOf("urgency", "payment_request"),
        why: List<WhyItem> = listOf(
            WhyItem("evt_old", now.minusSeconds(3 * 24 * 3600), "Courier SMS with a link"),
            WhyItem("evt_old2", now.minusSeconds(24 * 3600), "Unknown call, 4 minutes"),
        ),
    ) = Alert(id, eventId, tier, "Likely scam: payment demand", why, "Do not pay. Call your bank.", signals, createdAt)
}

class FakeSettings : AppSettings {
    val done = MutableStateFlow(false)
    private val intents = MutableStateFlow(mapOf(ConsentSource.Check to true))
    val paused = MutableStateFlow(false)
    val reactionMap = MutableStateFlow<Map<String, FeedbackLabel>>(emptyMap())
    val notified = MutableStateFlow<Set<String>?>(null)
    val trueBlack = MutableStateFlow(false)
    var cleared = 0

    override val onboardingDone: Flow<Boolean> = done
    override suspend fun setOnboardingDone(done: Boolean) { this.done.value = done }
    override fun consentIntent(source: ConsentSource): Flow<Boolean> = intents.map { it[source] ?: false }
    override suspend fun setConsentIntent(source: ConsentSource, on: Boolean) {
        intents.update { it + (source to (on || source == ConsentSource.Check)) }
    }
    override val monitoringPaused: Flow<Boolean> = paused
    override suspend fun setMonitoringPaused(paused: Boolean) { this.paused.value = paused }
    override val reactions: Flow<Map<String, FeedbackLabel>> = reactionMap
    override suspend fun setReaction(alertId: String, label: FeedbackLabel?) {
        reactionMap.update { if (label == null) it - alertId else it + (alertId to label) }
    }
    override val notifiedAlertIds: Flow<Set<String>?> = notified
    override suspend fun markNotified(ids: Set<String>) { notified.update { (it ?: emptySet()) + ids } }
    override val useTrueBlack: Flow<Boolean> = trueBlack
    override suspend fun setUseTrueBlack(enabled: Boolean) { trueBlack.value = enabled }
    override suspend fun installId() = "install-1"
    override suspend fun clearAll() {
        cleared++
        done.value = false
        intents.value = mapOf(ConsentSource.Check to true)
        paused.value = false
        reactionMap.value = emptyMap()
        notified.value = null
    }
}

class FakeSession : DeviceSession {
    var registered = false
    var registerCalls = 0
    var registerResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var resets = 0
    override suspend fun isRegistered() = registered
    override suspend fun ensureRegistered(): ApiResult<Unit> {
        if (registered) return ApiResult.Success(Unit)
        registerCalls++
        val r = registerResult
        if (r is ApiResult.Success) registered = true
        return r
    }
    override suspend fun reset() { resets++; registered = false }
    override suspend fun <T> authed(block: suspend () -> ApiResult<T>): ApiResult<T> = block()
}

class FakeAlerts(initial: List<Alert> = emptyList()) : AlertsRepository {
    private val _alerts = MutableStateFlow(initial)
    override val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()
    var refreshResult: ApiResult<List<Alert>>? = null // null = Success(current)
    var refreshCalls = 0
    val feedback = mutableListOf<Pair<String, FeedbackLabel>>()
    var feedbackResult: ApiResult<Unit> = ApiResult.Success(Unit)
    val remembered = mutableListOf<Alert>()
    var cleared = false

    fun set(list: List<Alert>) { _alerts.value = list }
    override suspend fun refresh(): ApiResult<List<Alert>> {
        refreshCalls++
        val r = refreshResult ?: ApiResult.Success(_alerts.value)
        if (r is ApiResult.Success) _alerts.value = r.value
        return r
    }
    override suspend fun get(id: String) = _alerts.value.firstOrNull { it.id == id } ?: remembered.firstOrNull { it.id == id }
    override suspend fun remember(alert: Alert) { remembered += alert }
    override suspend fun sendFeedback(alertId: String, label: FeedbackLabel): ApiResult<Unit> {
        feedback += alertId to label
        return feedbackResult
    }
    override fun clear() { cleared = true; _alerts.value = emptyList() }
}

class FakeChecks : CheckRepository {
    var result: ApiResult<Alert> = ApiResult.Success(Samples.alert("chk_1", Tier.High))
    val texts = mutableListOf<Triple<String, String?, String>>()
    val images = mutableListOf<Pair<Int, String>>()
    /** When set, calls suspend until completed (to test cancel / loading). */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun checkText(text: String, sender: String?, lang: String): ApiResult<Alert> {
        texts += Triple(text, sender, lang)
        gate?.await()
        return result
    }
    override suspend fun checkImage(bytes: ByteArray, mimeType: String, lang: String): ApiResult<Alert> {
        images += bytes.size to mimeType
        gate?.await()
        return result
    }
}

class FakeConsent : ConsentRepository {
    val calls = mutableListOf<Pair<ConsentSource, Boolean>>()
    var result: ApiResult<Unit> = ApiResult.Success(Unit)
    override suspend fun sync(source: ConsentSource, granted: Boolean): ApiResult<Unit> {
        calls += source to granted
        return result
    }
}

class FakeFamily : FamilyRepository {
    var invite: ApiResult<Invite> = ApiResult.Success(Invite("K7M2QX", Samples.now.plusSeconds(600)))
    var accept: ApiResult<Unit> = ApiResult.Success(Unit)
    var links: ApiResult<FamilyLinks> = ApiResult.Success(FamilyLinks(emptyList(), emptyList()))
    var unlink: ApiResult<Unit> = ApiResult.Success(Unit)
    var inviteCalls = 0
    val accepted = mutableListOf<Triple<String, String, String>>()
    val unlinked = mutableListOf<String>()
    override suspend fun createInvite(): ApiResult<Invite> { inviteCalls++; return invite }
    override suspend fun accept(code: String, name: String, fcmToken: String): ApiResult<Unit> {
        accepted += Triple(code, name, fcmToken); return accept
    }
    override suspend fun links() = links
    override suspend fun unlink(id: String): ApiResult<Unit> { unlinked += id; return unlink }
}

class FakeAccount(private val settings: FakeSettings? = null) : AccountRepository {
    var result: ApiResult<Unit> = ApiResult.Success(Unit)
    var calls = 0
    override suspend fun deleteEverything(): ApiResult<Unit> {
        calls++
        if (result is ApiResult.Success) settings?.clearAll()
        return result
    }
}

class FakeDemo(var runs: List<List<DemoStep>> = emptyList()) : DemoRepository {
    var calls = 0
    override fun run(): Flow<List<DemoStep>> = flow {
        calls++
        runs.forEach { emit(it) }
    }
}

class FakeLocale(var tag: String = "en") : LocaleController {
    override fun current() = tag
    override fun set(tag: String) { this.tag = tag }
}

class FakeAccess(
    var notification: Boolean = false,
    var callLog: Boolean = false,
    var post: Boolean = true,
) : SystemAccess {
    override fun notificationAccessGranted() = notification
    override fun callLogGranted() = callLog
    override fun postNotificationsGranted() = post
}

class FakeNotifier : AlertNotifier {
    val notified = mutableListOf<Alert>()
    var allowed = true
    override fun ensureChannels() = Unit
    override fun canNotify() = allowed
    override fun notify(alert: Alert) { if (allowed) notified += alert }
}

class FakeImages(var prepared: PreparedImage? = PreparedImage(ByteArray(1024), "image/png")) : ImagePreparer {
    override suspend fun prepare(uri: String) = prepared
    override suspend fun thumbnail(uri: String, maxPx: Int): Bitmap? = null
}

class FakeFcm : FcmTokenProvider {
    override suspend fun token() = "fcm-test-token"
}

/** Complete graph of fakes for ViewModel and Compose tests. */
class FakeGraph(
    override val settings: FakeSettings = FakeSettings(),
    override val session: FakeSession = FakeSession(),
    override val alerts: FakeAlerts = FakeAlerts(),
    override val checks: FakeChecks = FakeChecks(),
    override val consent: FakeConsent = FakeConsent(),
    override val family: FakeFamily = FakeFamily(),
    override val account: FakeAccount = FakeAccount(settings),
    override val demo: FakeDemo = FakeDemo(),
    override val locale: FakeLocale = FakeLocale(),
    override val access: FakeAccess = FakeAccess(),
    override val notifier: FakeNotifier = FakeNotifier(),
    override val images: FakeImages = FakeImages(),
    override val shareIntake: ShareIntake = ShareIntake(),
    override val fcm: FakeFcm = FakeFcm(),
    override val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    override val demoEnabled: Boolean = true,
    override val debugInfo: DebugInfo? = DebugInfo("http://test", "debug"),
) : AppGraph {
    override val clock: () -> Instant = { Samples.now }
}

/** Real SecureTokenStore over a temp DataStore file with a reversible fake cipher (no Keystore needed). */
fun testTokenStore(file: java.io.File, scope: CoroutineScope): ai.enthral.sentinel.data.store.SecureTokenStore {
    val cipher = object : ai.enthral.sentinel.data.store.TokenCipher {
        override fun encrypt(plain: ByteArray) = "ENC".toByteArray() + plain.reversedArray()
        override fun decrypt(blob: ByteArray) = if (blob.size < 3) null else blob.copyOfRange(3, blob.size).reversedArray()
    }
    return ai.enthral.sentinel.data.store.SecureTokenStore(
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { file },
        cipher,
        encode = { java.util.Base64.getEncoder().encodeToString(it) },
        decode = { java.util.Base64.getDecoder().decode(it) },
    )
}

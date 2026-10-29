package ai.enthral.sentinel.capture

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.notify.AlertNotifier
import ai.enthral.sentinel.data.repo.DeviceSession
import ai.enthral.sentinel.data.store.AppSettings
import ai.enthral.sentinel.data.store.TokenCipher
import ai.enthral.sentinel.data.system.SystemAccess
import ai.enthral.sentinel.sync.AlertDispatcher
import ai.enthral.sentinel.sync.DrainResult
import ai.enthral.sentinel.sync.EncryptedFileOutbox
import ai.enthral.sentinel.sync.EventSender
import ai.enthral.sentinel.sync.WorkManagerSyncScheduler
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the UI may read about capture: numbers and states only. */
interface CaptureStatus {
    val stats: Flow<StatsSnapshot>
    val queueSize: StateFlow<Int>

    /** Allowlisted packages (release list; the debug-only adb shell is not shown). */
    val watchedPackages: List<String>

    /** Events per package in the current 10-minute rate window. */
    fun rateWindowCounts(): Map<String, Int>
    suspend fun clearQueue(): Int
}

/** Production wiring of the capture -> outbox -> sync pipeline. Built lazily by AppContainer. */
class CaptureModule(
    private val context: Context,
    api: SentinelApi,
    session: DeviceSession,
    private val settings: AppSettings,
    notifier: AlertNotifier,
    private val access: SystemAccess,
    private val cipher: TokenCipher,
) : CaptureStatus {
    private val clock: () -> Long = System::currentTimeMillis

    /** Serial: notifications are processed one at a time, in arrival order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val state = DataStoreCaptureState(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            context.preferencesDataStoreFile("capture_state")
        },
    )
    private val dir = File(context.noBackupFilesDir, "capture")

    val outbox = EncryptedFileOutbox(File(dir, "outbox.bin"), cipher, clock, onDropped = { state.add(Counter.Dropped, it.toLong()) })
    val scheduler = WorkManagerSyncScheduler(context)
    val dispatcher = AlertDispatcher(settings, notifier)
    val sender = EventSender(api, session, settings, outbox, state, dispatcher, scheduler, clock)
    private val guard = CaptureGuard(clock)
    val allowlist: Map<String, ai.enthral.sentinel.domain.Source> = CaptureAllowlist.active

    val pipeline: CapturePipeline by lazy {
        CapturePipeline(
            gate = SettingsCaptureGate(settings) { access.notificationAccessGranted() },
            filter = NotificationFilter(allowlist, context.packageName),
            cursors = state,
            guard = guard,
            senders = SenderPolicy(Pseudonymizer(pseudonymKey()), AndroidContactChecker(context)),
            outbox = outbox,
            stats = state,
            clock = clock,
            onQueued = ::sendNow,
        )
    }

    init {
        scope.launch { outbox.warmUp() }
    }

    /** Fast path: try right away; whatever is left goes to the WorkManager drain with backoff. */
    fun sendNow() {
        sendScope.launch {
            when (sender.drain()) {
                DrainResult.Done, DrainResult.NotReady -> Unit
                DrainResult.Retry -> scheduler.scheduleSync()
                DrainResult.Backoff -> scheduler.scheduleSync(delayMs = 60_000L)
            }
        }
    }

    override val stats: Flow<StatsSnapshot> = state.snapshot
    override val queueSize: StateFlow<Int> = outbox.size
    override val watchedPackages: List<String> = CaptureAllowlist.userVisible
    override fun rateWindowCounts(): Map<String, Int> = guard.counts()
    override suspend fun clearQueue(): Int = outbox.clear()

    /** Delete-my-data: queue, counters, cursors, pseudonym key, scheduled work. */
    suspend fun wipe() {
        outbox.clear()
        state.clear()
        File(dir, KEY_FILE).delete()
        scheduler.cancelAll()
    }

    /** Per-install HMAC key for contact pseudonyms, encrypted with the Keystore cipher. Regenerated if unreadable. */
    private fun pseudonymKey(): ByteArray = synchronized(this) {
        val f = File(dir, KEY_FILE)
        if (f.exists()) cipher.decrypt(f.readBytes())?.takeIf { it.size == 32 }?.let { return it }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        dir.mkdirs()
        f.writeBytes(cipher.encrypt(key))
        key
    }

    private companion object {
        const val KEY_FILE = "pseudonym.key"
    }
}

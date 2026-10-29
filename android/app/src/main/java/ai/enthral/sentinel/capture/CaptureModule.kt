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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the UI may read about capture: numbers and states only. */
interface CaptureStatus {
    val stats: Flow<StatsSnapshot>
    val queueSize: StateFlow<Int>

    /** Allowlisted packages (release list; the debug-only adb shell is not shown). */
    val watchedPackages: List<String>

    /** Watched apps that are installed on this phone, with their display names (sorted by name). */
    fun installedWatchedApps(): List<WatchedApp>

    /** Events per package in the current 10-minute rate window. */
    fun rateWindowCounts(): Map<String, Int>
    suspend fun clearQueue(): Int
}

data class WatchedApp(val packageName: String, val label: String)

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
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + failureHandler())
    private val sendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + failureHandler())

    /** Last line of defence: an unexpected exception is counted (no content) instead of crashing the process. */
    private fun failureHandler() = CoroutineExceptionHandler { _, _ -> countFailure() }

    fun countFailure() {
        CoroutineScope(Dispatchers.IO).launch { runCatching { state.add(Counter.Failed) } }
    }

    private val state = DataStoreCaptureState(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            context.preferencesDataStoreFile("capture_state")
        },
    )
    private val dir = File(context.noBackupFilesDir, "capture")
    private val pseudonymKeys = PseudonymKeyStore(File(dir, KEY_FILE), cipher)

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
            senders = SenderPolicy(Pseudonymizer(pseudonymKeys::key), contacts),
            outbox = outbox,
            stats = state,
            clock = clock,
            onQueued = ::sendNow,
        )
    }

    private val e164 by lazy { E164Formatter(context) }
    private val contacts by lazy { AndroidContactChecker(context) }

    /** Call-log capture (P3): gated by the Call-info consent and READ_CALL_LOG. */
    val callLog: CallLogCapture by lazy {
        CallLogCapture(
            gate = ::callsAllowed,
            source = AndroidCallLogSource(context),
            state = state,
            outbox = outbox,
            stats = state,
            e164 = { e164.format(it) },
            isContact = { contacts.isSavedNumber(it) },
            clock = clock,
            onQueued = ::sendNow,
        )
    }

    /** Call screening needs the Call-info consent but not READ_CALL_LOG (the number comes from the screening API). */
    suspend fun screeningAllowed(): Boolean =
        settings.onboardingDone.first() &&
            settings.consentIntent(ai.enthral.sentinel.domain.ConsentSource.Calls).first() &&
            !settings.monitoringPaused.first()

    /**
     * From the screening service (main thread, must return fast). The event goes into the ENCRYPTED outbox on the
     * capture thread; the expedited job only drains, so the caller's number never sits in WorkManager's plain database.
     */
    fun onIncomingCall(number: String?, at: Long) {
        scope.launch {
            if (screenIncomingCall(number, at)) {
                scheduler.scheduleScreeningDrain()
                sendNow()
            }
        }
    }

    /**
     * Ringing call -> event (number, time, contact yes/no, duration 0, direction in) -> encrypted outbox. The call is
     * remembered (hashed number + time) so the later call-log row is not sent a second time. Returns true if queued.
     */
    suspend fun screenIncomingCall(number: String?, at: Long): Boolean {
        if (!screeningAllowed()) return false
        return try {
            val raw = number?.trim().orEmpty()
            val formatted = if (CallEvents.isHidden(raw)) "" else e164.format(raw) ?: raw
            val ev = CallEvents.toEvent(CallRow(0, raw, at, 0, CallEvents.INCOMING), { e164.format(it) },
                { contacts.isSavedNumber(it) }, ringing = true) ?: return false
            if (formatted.isNotEmpty()) state.addScreened(formatted, at)
            val added = outbox.add(ai.enthral.sentinel.sync.OutboxItem(ev, enqueuedAt = clock(), ringing = true))
            if (added) {
                state.add(Counter.Captured)
                state.markCaptured(clock())
            }
            added
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            countFailure()
            false
        }
    }

    suspend fun callsAllowed(): Boolean =
        settings.onboardingDone.first() &&
            settings.consentIntent(ai.enthral.sentinel.domain.ConsentSource.Calls).first() &&
            !settings.monitoringPaused.first() &&
            access.callLogGranted()

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

    override fun installedWatchedApps(): List<WatchedApp> {
        val pm = context.packageManager
        return watchedPackages.mapNotNull { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                WatchedApp(pkg, pm.getApplicationLabel(info).toString())
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                null
            }
        }.sortedBy { it.label.lowercase() }
    }
    override suspend fun clearQueue(): Int = outbox.clear()

    /** Delete-my-data: queue, counters, cursors, pseudonym key, scheduled work. */
    suspend fun wipe() {
        outbox.clear()
        state.clear()
        pseudonymKeys.reset() // new key on next use: pseudonyms after a wipe are unlinkable to earlier ones
        scheduler.cancelAll()
    }

    private companion object {
        const val KEY_FILE = "pseudonym.key"
    }
}

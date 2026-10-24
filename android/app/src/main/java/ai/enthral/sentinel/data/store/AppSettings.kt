package ai.enthral.sentinel.data.store

import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.FeedbackLabel
import kotlinx.coroutines.flow.Flow

/** Non-secret persisted app state. Language is handled by the per-app locale API, not stored here. */
interface AppSettings {
    val onboardingDone: Flow<Boolean>
    suspend fun setOnboardingDone(done: Boolean)

    /** What the user asked for. The effective state also needs the system permission (see SystemAccess). */
    fun consentIntent(source: ConsentSource): Flow<Boolean>
    suspend fun setConsentIntent(source: ConsentSource, on: Boolean)

    val monitoringPaused: Flow<Boolean>
    suspend fun setMonitoringPaused(paused: Boolean)

    /** The user's own verdict per alert id ("this was a scam / fine"). */
    val reactions: Flow<Map<String, FeedbackLabel>>
    suspend fun setReaction(alertId: String, label: FeedbackLabel?)

    /** Alert ids already surfaced as a notification. Null until the first refresh takes a silent baseline. */
    val notifiedAlertIds: Flow<Set<String>?>
    suspend fun markNotified(ids: Set<String>)

    val useDynamicColor: Flow<Boolean>
    suspend fun setUseDynamicColor(enabled: Boolean)

    /** Random per-install id (used for the placeholder push token until Firebase is wired). */
    suspend fun installId(): String

    /** Forget everything (delete-my-data). */
    suspend fun clearAll()
}

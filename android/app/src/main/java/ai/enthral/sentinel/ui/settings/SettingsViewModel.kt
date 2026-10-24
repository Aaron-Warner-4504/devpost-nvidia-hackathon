package ai.enthral.sentinel.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.enthral.sentinel.AppGraph
import ai.enthral.sentinel.DebugInfo
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.repo.AccountRepository
import ai.enthral.sentinel.data.repo.ConsentRepository
import ai.enthral.sentinel.data.store.AppSettings
import ai.enthral.sentinel.data.system.LocaleController
import ai.enthral.sentinel.data.system.SystemAccess
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.ui.common.Events
import ai.enthral.sentinel.ui.common.SourcesController
import ai.enthral.sentinel.ui.common.SourcesState
import ai.enthral.sentinel.ui.onboarding.SystemRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The word the user types to confirm deleting everything. */
const val DELETE_CONFIRM_WORD = "DELETE"

fun isDeleteConfirmed(typed: String) = typed.trim().equals(DELETE_CONFIRM_WORD, ignoreCase = true)

data class SettingsUiState(
    val sources: SourcesState = SourcesState(),
    val paused: Boolean = false,
    val trueBlack: Boolean = false,
    val language: String = "en",
    val deleting: Boolean = false,
    val deleteFailure: ApiResult<Nothing>? = null,
    val consentSyncFailed: Boolean = false,
    val debugInfo: DebugInfo? = null,
    val demoEnabled: Boolean = false,
)

sealed interface SettingsEvent {
    data class Request(val request: SystemRequest) : SettingsEvent
    data object RequestPostNotifications : SettingsEvent
}

class SettingsViewModel(
    private val settings: AppSettings,
    access: SystemAccess,
    private val consent: ConsentRepository,
    private val account: AccountRepository,
    private val locale: LocaleController,
    private val debugInfo: DebugInfo?,
    private val demoEnabled: Boolean,
) : ViewModel() {
    private val sources = SourcesController(settings, access, viewModelScope)
    private val extra = MutableStateFlow(Extra())
    private val lastSynced = mutableMapOf<ConsentSource, Boolean>()
    val events = Events<SettingsEvent>()

    private data class Extra(val deleting: Boolean = false, val failure: ApiResult<Nothing>? = null,
                             val syncFailed: Boolean = false)

    val state: StateFlow<SettingsUiState> = combine(
        sources.state, settings.monitoringPaused, settings.useTrueBlack, extra,
    ) { s, paused, dyn, e ->
        SettingsUiState(s, paused, dyn, locale.current(), e.deleting, e.failure, e.syncFailed, debugInfo, demoEnabled)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(debugInfo = debugInfo, demoEnabled = demoEnabled))

    /** On resume: re-read OS permissions and tell the server about any change the user made in system settings. */
    fun onResume() {
        sources.refreshAccess()
        viewModelScope.launch { reconcile() }
    }

    private suspend fun reconcile() {
        val s = sources.state.value
        for (src in listOf(ConsentSource.Notifications, ConsentSource.Calls)) {
            val effective = s.effective(src)
            if (lastSynced[src] == effective) continue
            if (pushConsent(src, effective)) lastSynced[src] = effective
        }
    }

    private suspend fun pushConsent(src: ConsentSource, granted: Boolean): Boolean {
        val ok = consent.sync(src, granted) is ApiResult.Success
        extra.update { it.copy(syncFailed = !ok) }
        return ok
    }

    fun setSource(source: ConsentSource, on: Boolean) {
        viewModelScope.launch {
            sources.setIntent(source, on)
            val s = sources.state.value
            if (on) {
                when (source) {
                    ConsentSource.Notifications ->
                        if (!s.notificationAccess) events.send(SettingsEvent.Request(SystemRequest.OpenNotificationAccess))
                    ConsentSource.Calls -> if (!s.callAccess) {
                        events.send(SettingsEvent.Request(
                            if (s.callPermissionBlocked) SystemRequest.OpenAppSettings else SystemRequest.RequestCallPermission,
                        ))
                    }
                    ConsentSource.Check -> Unit
                }
            }
            // Turning off is immediate locally; the server is told right away (and again on resume if that fails).
            val effective = sources.state.value.effective(source)
            if (pushConsent(source, effective)) lastSynced[source] = effective
        }
    }

    fun fixSource(source: ConsentSource) = setSource(source, true)

    fun onCallPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        sources.onCallPermissionResult(blocked = !granted && permanentlyDenied)
        viewModelScope.launch { reconcile() }
    }

    fun setPaused(paused: Boolean) {
        viewModelScope.launch { settings.setMonitoringPaused(paused) }
    }

    fun setTrueBlack(on: Boolean) {
        viewModelScope.launch { settings.setUseTrueBlack(on) }
    }

    fun setLanguage(tag: String) = locale.set(tag)

    fun requestPostNotifications() = events.send(SettingsEvent.RequestPostNotifications)

    fun onPostNotificationsResult() = sources.refreshAccess()

    /** Caller has already checked [isDeleteConfirmed]. On success the root observes onboarding state and resets. */
    fun deleteEverything() {
        if (extra.value.deleting) return
        extra.update { it.copy(deleting = true, failure = null) }
        viewModelScope.launch {
            val r = account.deleteEverything()
            extra.update {
                it.copy(deleting = false, failure = when (r) {
                    is ApiResult.Success -> null
                    is ApiResult.HttpError -> r
                    is ApiResult.NetworkError -> r
                })
            }
        }
    }

    fun dismissDeleteFailure() = extra.update { it.copy(failure = null) }

    companion object {
        fun create(g: AppGraph) = SettingsViewModel(
            g.settings, g.access, g.consent, g.account, g.locale, g.debugInfo, g.demoEnabled,
        )
    }
}

package ai.enthral.sentinel.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.enthral.sentinel.AppGraph
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.repo.ConsentRepository
import ai.enthral.sentinel.data.repo.DeviceSession
import ai.enthral.sentinel.data.store.AppSettings
import ai.enthral.sentinel.data.system.LocaleController
import ai.enthral.sentinel.data.system.SystemAccess
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.ui.common.Events
import ai.enthral.sentinel.ui.common.SourcesController
import ai.enthral.sentinel.ui.common.SourcesState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Things only the UI can do (they need an Activity). */
sealed interface SystemRequest {
    data object OpenNotificationAccess : SystemRequest
    data object RequestCallPermission : SystemRequest
    data object OpenAppSettings : SystemRequest
}

data class OnboardingState(
    val sources: SourcesState = SourcesState(),
    val finishing: Boolean = false,
    val failure: ApiResult<Nothing>? = null,
)

/**
 * Registration happens ONLY in [finish], after the user has seen and set every consent. Nothing here talks to
 * the server before that.
 */
class OnboardingViewModel(
    private val session: DeviceSession,
    private val consent: ConsentRepository,
    private val settings: AppSettings,
    access: SystemAccess,
    private val locale: LocaleController,
) : ViewModel() {
    private val sources = SourcesController(settings, access, viewModelScope)
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()
    val requests = Events<SystemRequest>()

    init {
        viewModelScope.launch {
            sources.state.collect { s -> _state.update { it.copy(sources = s) } }
        }
    }

    fun language(): String = locale.current()
    fun setLanguage(tag: String) = locale.set(tag)

    fun onResume() = sources.refreshAccess()

    /** User confirmed the "what we read" sheet for [source] (or flipped it off). */
    fun setSource(source: ConsentSource, on: Boolean) {
        viewModelScope.launch {
            sources.setIntent(source, on)
            if (on) requestSystemAccess(source)
        }
    }

    /** Re-ask for a source whose switch is on but the OS has not granted yet. */
    fun fixSource(source: ConsentSource) = requestSystemAccess(source)

    private fun requestSystemAccess(source: ConsentSource) {
        val s = sources.state.value
        when (source) {
            ConsentSource.Notifications ->
                if (!s.notificationAccess) requests.send(SystemRequest.OpenNotificationAccess)
            ConsentSource.Calls ->
                if (!s.callAccess) {
                    requests.send(
                        if (s.callPermissionBlocked) SystemRequest.OpenAppSettings
                        else SystemRequest.RequestCallPermission,
                    )
                }
            ConsentSource.Check -> Unit
        }
    }

    /** [permanentlyDenied]: the system showed its dialog and the user said no without any way to ask again. */
    fun onCallPermissionResult(granted: Boolean, permanentlyDenied: Boolean) =
        sources.onCallPermissionResult(blocked = !granted && permanentlyDenied)

    fun finish() {
        if (_state.value.finishing) return
        viewModelScope.launch {
            _state.update { it.copy(finishing = true, failure = null) }
            val failure = register() ?: syncConsents()
            if (failure == null) settings.setOnboardingDone(true)
            _state.update { it.copy(finishing = false, failure = failure) }
        }
    }

    private suspend fun register(): ApiResult<Nothing>? = when (val r = session.ensureRegistered()) {
        is ApiResult.Success -> null
        is ApiResult.HttpError -> r
        is ApiResult.NetworkError -> r
    }

    private suspend fun syncConsents(): ApiResult<Nothing>? {
        val s = sources.state.value
        for (source in ConsentSource.entries) {
            when (val r = consent.sync(source, s.effective(source))) {
                is ApiResult.Success -> Unit
                is ApiResult.HttpError -> return r
                is ApiResult.NetworkError -> return r
            }
        }
        return null
    }

    companion object {
        fun create(g: AppGraph) = OnboardingViewModel(g.session, g.consent, g.settings, g.access, g.locale)
    }
}

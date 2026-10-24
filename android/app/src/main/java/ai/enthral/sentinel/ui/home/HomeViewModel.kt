package ai.enthral.sentinel.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.enthral.sentinel.AppGraph
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.repo.AlertsRepository
import ai.enthral.sentinel.data.store.AppSettings
import ai.enthral.sentinel.data.system.SystemAccess
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.ProtectionStatus
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.domain.computeProtection
import ai.enthral.sentinel.ui.common.SourcesController
import ai.enthral.sentinel.ui.common.SourcesState
import ai.enthral.sentinel.ui.common.countsToday
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase { Loading, Ready, Error }

data class HomeUiState(
    val phase: Phase = Phase.Loading,
    val refreshing: Boolean = false,
    val failure: ApiResult<Nothing>? = null,
    val protection: ProtectionStatus = ProtectionStatus.SetupIncomplete,
    val sources: SourcesState = SourcesState(),
    val latest: Alert? = null,
    val todayCounts: Map<Tier, Int> = emptyMap(),
    val offline: Boolean = false,
)

class HomeViewModel(
    private val alerts: AlertsRepository,
    settings: AppSettings,
    access: SystemAccess,
    private val clock: () -> Instant,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val sources = SourcesController(settings, access, viewModelScope)
    private val fetch = MutableStateFlow(Fetch())
    private val paused = settings.monitoringPaused

    private data class Fetch(val phase: Phase = Phase.Loading, val refreshing: Boolean = false,
                             val failure: ApiResult<Nothing>? = null, val offline: Boolean = false)

    val state: StateFlow<HomeUiState> = combine(fetch, alerts.alerts, sources.state, paused) { f, list, s, p ->
        val protection = computeProtection(s.notifications, s.calls, p, f.offline)
        HomeUiState(
            phase = f.phase,
            refreshing = f.refreshing,
            failure = f.failure,
            protection = protection,
            sources = s,
            latest = list.maxByOrNull { it.createdAt },
            todayCounts = countsToday(list, clock(), zone),
            offline = f.offline,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    init {
        refresh(initial = true)
    }

    fun onResume() = sources.refreshAccess()

    fun refresh(initial: Boolean = false) {
        viewModelScope.launch {
            fetch.update { it.copy(phase = if (initial) Phase.Loading else it.phase, refreshing = !initial) }
            when (val r = alerts.refresh()) {
                is ApiResult.Success -> fetch.value = Fetch(Phase.Ready)
                is ApiResult.NetworkError ->
                    // Keep showing what we already have; only a cold start with nothing cached is a hard error.
                    fetch.value = if (alerts.alerts.value.isNotEmpty()) Fetch(Phase.Ready, offline = true)
                    else Fetch(Phase.Error, failure = r, offline = true)
                is ApiResult.HttpError -> fetch.value = Fetch(Phase.Error, failure = r)
            }
        }
    }

    companion object {
        fun create(g: AppGraph) = HomeViewModel(g.alerts, g.settings, g.access, g.clock)
    }
}

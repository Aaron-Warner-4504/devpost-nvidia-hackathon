package ai.enthral.sentinel.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.enthral.sentinel.AppGraph
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.repo.AlertsRepository
import ai.enthral.sentinel.domain.Tier
import ai.enthral.sentinel.ui.common.AlertGroup
import ai.enthral.sentinel.ui.common.groupByDay
import ai.enthral.sentinel.ui.home.Phase
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AlertFilter(val tier: Tier?) { All(null), Critical(Tier.Critical), High(Tier.High), Caution(Tier.Caution) }

data class AlertsUiState(
    val phase: Phase = Phase.Loading,
    val refreshing: Boolean = false,
    val failure: ApiResult<Nothing>? = null,
    val filter: AlertFilter = AlertFilter.All,
    val groups: List<AlertGroup> = emptyList(),
    /** True when there are alerts at all but the filter hides every one. */
    val filteredOut: Boolean = false,
)

class AlertsViewModel(
    private val repo: AlertsRepository,
    private val clock: () -> Instant,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private data class Load(val phase: Phase = Phase.Loading, val refreshing: Boolean = false,
                            val failure: ApiResult<Nothing>? = null)

    private val load = MutableStateFlow(Load())
    private val filter = MutableStateFlow(AlertFilter.All)

    val state: StateFlow<AlertsUiState> = combine(load, filter, repo.alerts) { l, f, all ->
        val shown = if (f.tier == null) all else all.filter { it.tier == f.tier }
        AlertsUiState(
            phase = if (l.phase == Phase.Error && all.isNotEmpty()) Phase.Ready else l.phase,
            refreshing = l.refreshing,
            failure = l.failure,
            filter = f,
            groups = groupByDay(shown, clock(), zone),
            filteredOut = all.isNotEmpty() && shown.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AlertsUiState())

    init {
        refresh(initial = true)
    }

    fun setFilter(f: AlertFilter) {
        filter.value = f
    }

    fun refresh(initial: Boolean = false) {
        viewModelScope.launch {
            load.update { it.copy(phase = if (initial) Phase.Loading else it.phase, refreshing = !initial) }
            load.value = when (val r = repo.refresh()) {
                is ApiResult.Success -> Load(Phase.Ready)
                is ApiResult.HttpError -> Load(Phase.Error, failure = r)
                is ApiResult.NetworkError -> Load(Phase.Error, failure = r)
            }
        }
    }

    companion object {
        fun create(g: AppGraph) = AlertsViewModel(g.alerts, g.clock)
    }
}

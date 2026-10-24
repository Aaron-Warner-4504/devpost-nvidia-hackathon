package ai.enthral.sentinel.ui.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.enthral.sentinel.AppGraph
import ai.enthral.sentinel.data.demo.DemoOutcome
import ai.enthral.sentinel.data.demo.DemoRepository
import ai.enthral.sentinel.data.demo.DemoStep
import ai.enthral.sentinel.ui.common.Events
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DemoPhase { Idle, Running, Done, Failed }

data class DemoUiState(val phase: DemoPhase = DemoPhase.Idle, val steps: List<DemoStep> = emptyList()) {
    /** The strongest alert the demo produced (the story ends on a critical one). */
    val finalAlertId: String? get() = steps.mapNotNull { (it.outcome as? DemoOutcome.Flagged)?.alert }
        .maxByOrNull { it.tier.ordinal }?.id
}

sealed interface DemoEvent {
    data class OpenAlert(val id: String) : DemoEvent
}

class DemoViewModel(private val demo: DemoRepository) : ViewModel() {
    private val _state = MutableStateFlow(DemoUiState())
    val state: StateFlow<DemoUiState> = _state.asStateFlow()
    val events = Events<DemoEvent>()
    private var job: Job? = null

    /** Safe to run repeatedly: the repository uses stable event ids, so the server reports duplicates. */
    fun start() {
        if (job?.isActive == true) return
        _state.value = DemoUiState(DemoPhase.Running)
        job = viewModelScope.launch {
            var last: List<DemoStep> = emptyList()
            demo.run().collect { steps ->
                last = steps
                _state.value = DemoUiState(DemoPhase.Running, steps)
            }
            val failed = last.any { it.outcome is DemoOutcome.Failed }
            val done = DemoUiState(if (failed) DemoPhase.Failed else DemoPhase.Done, last)
            _state.value = done
            done.finalAlertId?.takeIf { !failed }?.let { events.send(DemoEvent.OpenAlert(it)) }
        }
    }

    companion object {
        fun create(g: AppGraph) = DemoViewModel(g.demo)
    }
}

package ai.enthral.sentinel.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.enthral.sentinel.AppGraph
import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.repo.AlertsRepository
import ai.enthral.sentinel.data.store.AppSettings
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.FeedbackLabel
import ai.enthral.sentinel.ui.common.Events
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DetailState {
    data object Loading : DetailState
    data object NotFound : DetailState
    data class Content(val alert: Alert, val reaction: FeedbackLabel?) : DetailState
}

sealed interface DetailEvent {
    /** Feedback applied on screen; it is sent to the server unless the user undoes it. */
    data class ShowUndo(val label: FeedbackLabel) : DetailEvent
    data object FeedbackFailed : DetailEvent
}

/**
 * Feedback is optimistic: the UI updates at once and an Undo snackbar is shown. The POST is deferred by
 * [undoWindowMs] so Undo needs no "unset" call (the API can only flip between labels). If the user leaves the
 * screen first, the pending verdict is sent immediately from [appScope] instead of being dropped.
 */
class AlertDetailViewModel(
    private val alertId: String,
    private val repo: AlertsRepository,
    private val settings: AppSettings,
    private val appScope: CoroutineScope,
    private val undoWindowMs: Long = 5_000,
) : ViewModel() {
    private data class Pending(val label: FeedbackLabel, val previous: FeedbackLabel?)

    private val loaded = MutableStateFlow<Alert?>(null)
    private val notFound = MutableStateFlow(false)
    private var pending: Pending? = null
    private var commitJob: Job? = null
    val events = Events<DetailEvent>()

    val state: StateFlow<DetailState> = combine(loaded, notFound, settings.reactions) { a, nf, reactions ->
        when {
            a != null -> DetailState.Content(a, reactions[alertId])
            nf -> DetailState.NotFound
            else -> DetailState.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DetailState.Loading)

    init {
        viewModelScope.launch {
            val alert = repo.get(alertId)
            if (alert != null) loaded.value = alert else notFound.value = true
        }
    }

    fun onFeedback(label: FeedbackLabel) {
        val current = (state.value as? DetailState.Content) ?: return
        if (current.reaction == label && pending == null) return
        // A second tap inside the undo window replaces the first; only the latest verdict is sent.
        val queued = pending
        commitJob?.cancel()
        val previous = if (queued != null) queued.previous else current.reaction
        pending = Pending(label, previous)
        viewModelScope.launch { settings.setReaction(alertId, label) }
        events.send(DetailEvent.ShowUndo(label))
        commitJob = viewModelScope.launch {
            delay(undoWindowMs)
            val p = pending ?: return@launch
            pending = null
            send(p)
        }
    }

    fun undo() {
        val p = pending ?: return
        commitJob?.cancel()
        pending = null
        viewModelScope.launch { settings.setReaction(alertId, p.previous) }
    }

    private suspend fun send(p: Pending) {
        when (repo.sendFeedback(alertId, p.label)) {
            is ApiResult.Success -> Unit
            else -> {
                settings.setReaction(alertId, p.previous)
                events.send(DetailEvent.FeedbackFailed)
            }
        }
    }

    override fun onCleared() {
        val p = pending ?: return
        pending = null
        appScope.launch { repo.sendFeedback(alertId, p.label) }
    }

    companion object {
        fun create(g: AppGraph, id: String) = AlertDetailViewModel(id, g.alerts, g.settings, g.appScope)
    }
}

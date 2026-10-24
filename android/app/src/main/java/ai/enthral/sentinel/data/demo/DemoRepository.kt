package ai.enthral.sentinel.data.demo

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.api.toDomain
import ai.enthral.sentinel.data.repo.AlertsRepository
import ai.enthral.sentinel.data.repo.DeviceSession
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface DemoRepository {
    /** Emits the full step list after every change; completes after the last step or the first failure. */
    fun run(): Flow<List<DemoStep>>
}

class NetworkDemoRepository(
    private val api: SentinelApi,
    private val session: DeviceSession,
    private val alerts: AlertsRepository,
    private val clock: () -> Instant = Instant::now,
) : DemoRepository {

    override fun run(): Flow<List<DemoStep>> = flow {
        val events = DemoTimeline.events(clock())
        var steps = DemoTimeline.keys.map { DemoStep(it, DemoOutcome.Pending) }
        emit(steps)
        for ((i, event) in events.withIndex()) {
            steps = steps.replace(i, DemoOutcome.Running)
            emit(steps)
            val outcome = when (val r = session.authed { api.postEvent(event) }) {
                is ApiResult.Success -> {
                    val alert = r.value.alert?.toDomain()
                        ?: if (r.value.duplicate) {
                            // Re-run: the server will not re-send the alert, so read it back from history.
                            alerts.refresh()
                            alerts.alerts.value.firstOrNull { it.eventId == r.value.eventId }
                        } else null
                    if (alert != null) DemoOutcome.Flagged(alert) else DemoOutcome.NoAlert
                }
                is ApiResult.HttpError -> DemoOutcome.Failed(r)
                is ApiResult.NetworkError -> DemoOutcome.Failed(r)
            }
            steps = steps.replace(i, outcome)
            emit(steps)
            if (outcome is DemoOutcome.Failed) return@flow
        }
        alerts.refresh() // notifies the new critical alert and fills the history list
    }

    private fun List<DemoStep>.replace(index: Int, outcome: DemoOutcome) =
        mapIndexed { i, s -> if (i == index) s.copy(outcome = outcome) else s }
}

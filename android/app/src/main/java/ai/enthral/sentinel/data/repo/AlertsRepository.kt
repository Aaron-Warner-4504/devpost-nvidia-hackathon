package ai.enthral.sentinel.data.repo

import ai.enthral.sentinel.data.api.ApiResult
import ai.enthral.sentinel.data.api.SentinelApi
import ai.enthral.sentinel.data.api.map
import ai.enthral.sentinel.data.api.toDomain
import ai.enthral.sentinel.data.notify.AlertNotifier
import ai.enthral.sentinel.data.store.AppSettings
import ai.enthral.sentinel.domain.Alert
import ai.enthral.sentinel.domain.FeedbackLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Alert history. The in-memory cache is the seam where the encrypted local store (later phase) plugs in:
 * screens only talk to this interface.
 */
interface AlertsRepository {
    /** Newest first, as last fetched. Never contains `info` results of checks (the server does not list them). */
    val alerts: StateFlow<List<Alert>>

    suspend fun refresh(): ApiResult<List<Alert>>

    /** Cache lookup, falling back to one refresh (deep links, notifications). */
    suspend fun get(id: String): Alert?

    /**
     * Keeps a result that is not in the history list (e.g. an `info` check) viewable by id. It also counts as already
     * seen: the user asked for this result, so it must never come back later as a heads-up notification.
     */
    suspend fun remember(alert: Alert)

    suspend fun sendFeedback(alertId: String, label: FeedbackLabel): ApiResult<Unit>

    /** Drops everything cached (delete-my-data). */
    fun clear()
}

class NetworkAlertsRepository(
    private val api: SentinelApi,
    private val session: DeviceSession,
    private val settings: AppSettings,
    private val notifier: AlertNotifier,
) : AlertsRepository {
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    override val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()
    private val extras = MutableStateFlow<Map<String, Alert>>(emptyMap())

    override suspend fun refresh(): ApiResult<List<Alert>> {
        val result = session.authed { api.getAlerts() }.map { list -> list.map { it.toDomain() } }
        if (result is ApiResult.Success) {
            _alerts.value = result.value
            notifyNewAlerts(result.value)
        }
        return result
    }

    /** First refresh takes a silent baseline; later refreshes notify each new high/critical alert once. */
    private suspend fun notifyNewAlerts(list: List<Alert>) {
        val seen = settings.notifiedAlertIds.first()
        if (seen == null) {
            settings.markNotified(list.map { it.id }.toSet())
            return
        }
        val fresh = list.filter { it.id !in seen }
        if (fresh.isEmpty()) return
        fresh.filter { it.tier.interrupts }.sortedBy { it.createdAt }.forEach { notifier.notify(it) }
        settings.markNotified(fresh.map { it.id }.toSet())
    }

    override suspend fun get(id: String): Alert? {
        find(id)?.let { return it }
        refresh()
        return find(id)
    }

    private fun find(id: String): Alert? = _alerts.value.firstOrNull { it.id == id } ?: extras.value[id]

    override suspend fun remember(alert: Alert) {
        extras.value = extras.value + (alert.id to alert)
        if (settings.notifiedAlertIds.first() != null) settings.markNotified(setOf(alert.id))
    }

    override suspend fun sendFeedback(alertId: String, label: FeedbackLabel): ApiResult<Unit> =
        session.authed { api.postFeedback(alertId, label) }.map { }

    override fun clear() {
        _alerts.value = emptyList()
        extras.value = emptyMap()
    }
}

package ai.enthral.sentinel.ui.common

import ai.enthral.sentinel.data.store.AppSettings
import ai.enthral.sentinel.data.system.SystemAccess
import ai.enthral.sentinel.domain.ConsentSource
import ai.enthral.sentinel.domain.SourceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SourcesState(
    val notificationsIntent: Boolean = false,
    val callsIntent: Boolean = false,
    val notificationAccess: Boolean = false,
    val callAccess: Boolean = false,
    /** READ_CALL_LOG was denied and the system will not ask again: only app settings can fix it. */
    val callPermissionBlocked: Boolean = false,
    val postNotifications: Boolean = false,
) {
    val notifications: SourceStatus get() = status(notificationsIntent, notificationAccess)
    val calls: SourceStatus get() = status(callsIntent, callAccess)

    fun status(source: ConsentSource): SourceStatus = when (source) {
        ConsentSource.Notifications -> notifications
        ConsentSource.Calls -> calls
        ConsentSource.Check -> SourceStatus.On
    }

    /** What is really allowed right now: what the user asked for AND what the OS granted. */
    fun effective(source: ConsentSource): Boolean = status(source) == SourceStatus.On

    private fun status(intent: Boolean, granted: Boolean) = when {
        !intent -> SourceStatus.Off
        granted -> SourceStatus.On
        else -> SourceStatus.NeedsPermission
    }
}

/**
 * Shared by onboarding and settings: user intent (persisted) combined with live OS permission state.
 * State is updated synchronously by every method, so callers can read it right after changing it.
 */
class SourcesController(
    private val settings: AppSettings,
    private val access: SystemAccess,
    scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        SourcesState(
            notificationAccess = access.notificationAccessGranted(),
            callAccess = access.callLogGranted(),
            postNotifications = access.postNotificationsGranted(),
        ),
    )
    val state: StateFlow<SourcesState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(
                settings.consentIntent(ConsentSource.Notifications),
                settings.consentIntent(ConsentSource.Calls),
            ) { n, c -> n to c }.collect { (n, c) ->
                _state.update { it.copy(notificationsIntent = n, callsIntent = c) }
            }
        }
    }

    /** Call on ON_RESUME: the user may have changed permissions in system settings. */
    fun refreshAccess() = _state.update {
        val callGranted = access.callLogGranted()
        it.copy(
            notificationAccess = access.notificationAccessGranted(),
            callAccess = callGranted,
            callPermissionBlocked = it.callPermissionBlocked && !callGranted,
            postNotifications = access.postNotificationsGranted(),
        )
    }

    fun onCallPermissionResult(blocked: Boolean) {
        refreshAccess()
        _state.update { it.copy(callPermissionBlocked = blocked && !it.callAccess) }
    }

    suspend fun setIntent(source: ConsentSource, on: Boolean) {
        _state.update {
            when (source) {
                ConsentSource.Notifications -> it.copy(notificationsIntent = on)
                ConsentSource.Calls -> it.copy(callsIntent = on)
                ConsentSource.Check -> it
            }
        }
        settings.setConsentIntent(source, on)
    }
}

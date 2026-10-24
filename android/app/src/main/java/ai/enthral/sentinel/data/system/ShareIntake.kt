package ai.enthral.sentinel.data.system

import ai.enthral.sentinel.domain.SharedContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hand-off from MainActivity (ACTION_SEND) to the Check screen. Consumed once. */
class ShareIntake {
    private val _pending = MutableStateFlow<SharedContent?>(null)
    val pending: StateFlow<SharedContent?> = _pending.asStateFlow()

    fun offer(content: SharedContent) {
        _pending.value = content
    }

    fun consume() {
        _pending.value = null
    }
}

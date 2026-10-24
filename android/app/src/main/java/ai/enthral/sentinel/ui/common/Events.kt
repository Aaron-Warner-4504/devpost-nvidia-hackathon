package ai.enthral.sentinel.ui.common

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** One-shot UI events (navigation, snackbars, system intents). Buffered so none is lost while the UI is away. */
class Events<T> {
    private val channel = Channel<T>(Channel.BUFFERED)
    val flow: Flow<T> = channel.receiveAsFlow()
    fun send(event: T) {
        channel.trySend(event)
    }
}

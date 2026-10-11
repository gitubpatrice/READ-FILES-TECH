package com.readfilestech.read_files_tech.ui.common

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/** An action offered with a message ("Undo"). It runs once, if the user taps it in time. */
data class MessageAction(val label: UiText, val run: () -> Unit)

data class UserMessage(
    val text: UiText,
    val isError: Boolean = false,
    val action: MessageAction? = null,
)

/**
 * Messages for the user, shown by the app's root whatever screen is on top — `SnackTarget` of the
 * 2.15.x: a long copy the user left behind still reports how it ended, and an error is not silenced
 * because its screen closed meanwhile.
 *
 * A QUEUE, not a broadcast (Claude review R5): a message posted while nothing listens — the app in the
 * background, the activity between a rotation and its new composition, or closed while a copy goes on
 * — waits for the root to come back instead of vanishing. Each message is shown once. A burst beyond
 * the buffer drops the OLDEST, never the latest result.
 */
@Singleton
class UserMessages @Inject constructor() {
    private val queue = Channel<UserMessage>(CAPACITY, BufferOverflow.DROP_OLDEST)

    val messages: Flow<UserMessage> = queue.receiveAsFlow()

    /** The message on screen, until it goes: taken from the queue, it lives only here meanwhile. */
    @Volatile
    private var showing: UserMessage? = null

    /**
     * Shows each message once, in order, through [show], which returns when the message is gone. One
     * cut short — the app sent to the background, a rotation, a language change — is shown again first
     * when this is called again: it was out of the queue, and its "Undo" with it (Claude review R6).
     */
    suspend fun showEach(show: suspend (UserMessage) -> Unit) {
        showing?.let { interrupted ->
            show(interrupted)
            showing = null
        }
        messages.collect { message ->
            showing = message
            show(message)
            showing = null
        }
    }

    fun post(message: UserMessage) {
        queue.trySend(message)
    }

    fun info(text: UiText) = post(UserMessage(text))

    fun error(text: UiText) = post(UserMessage(text, isError = true))

    private companion object {
        const val CAPACITY = 16
    }
}

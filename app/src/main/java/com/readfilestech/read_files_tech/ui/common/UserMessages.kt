package com.readfilestech.read_files_tech.ui.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
 *
 * The head stays IN the queue while it is shown, and leaves it only once gone: one cut short — the app
 * sent to the background, a rotation, a language change — is shown again first, its "Undo" with it
 * (Claude review R6). A Channel could not promise it: a message received, its delivery cancelled before
 * it ran, was dropped (R7).
 */
@Singleton
class UserMessages @Inject constructor() {
    /**
     * One posting, equal only to itself: the same text posted twice is two messages. A data class here
     * would make a full queue of equal messages EQUAL to the next one, and StateFlow ignores an equal
     * value — the latest would be the one dropped.
     */
    private class Posted(val message: UserMessage)

    private val queue = MutableStateFlow<List<Posted>>(emptyList())

    /** Shows each message once, in order, through [show], which returns when the message is gone. */
    suspend fun showEach(show: suspend (UserMessage) -> Unit) {
        while (true) {
            val head = queue.first { it.isNotEmpty() }.first()
            show(head.message)
            // Dropped meanwhile by a burst, the head is no longer first: nothing else is removed.
            queue.update { posted -> if (posted.firstOrNull() === head) posted.drop(1) else posted }
        }
    }

    fun post(message: UserMessage) {
        queue.update { posted -> (posted + Posted(message)).takeLast(CAPACITY) }
    }

    fun info(text: UiText) = post(UserMessage(text))

    fun error(text: UiText) = post(UserMessage(text, isError = true))

    private companion object {
        const val CAPACITY = 16
    }
}

package com.readfilestech.read_files_tech.ui.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 */
@Singleton
class UserMessages @Inject constructor() {
    private val queue = MutableSharedFlow<UserMessage>(extraBufferCapacity = CAPACITY)

    val messages: SharedFlow<UserMessage> = queue.asSharedFlow()

    fun post(message: UserMessage) {
        queue.tryEmit(message)
    }

    fun info(text: UiText) = post(UserMessage(text))

    fun error(text: UiText) = post(UserMessage(text, isError = true))

    private companion object {
        const val CAPACITY = 16
    }
}

package com.readfilestech.read_files_tech.ui.common

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserMessagesTest {
    private val messages = UserMessages()
    private val first = UserMessage(uiText(R.string.error_io))
    private val second = UserMessage(uiText(R.string.error_not_found))

    /** The app sent to the background while a message is on screen: it comes back, then the next one. */
    @Test
    fun `a message cut short is shown again first`() = runTest {
        messages.post(first)
        messages.post(second)
        val cutShort = mutableListOf<UserMessage>()
        val onScreen = launch {
            messages.showEach { message ->
                cutShort += message
                awaitCancellation()
            }
        }
        runCurrent()
        onScreen.cancel()

        val shown = mutableListOf<UserMessage>()
        val back = launch { messages.showEach { shown += it } }
        runCurrent()
        back.cancel()

        assertThat(cutShort).containsExactly(first)
        assertThat(shown).containsExactly(first, second).inOrder()
    }

    /** Gone normally (timed out, dismissed, its action taken), it is never shown twice. */
    @Test
    fun `a message shown to its end is not shown again`() = runTest {
        messages.post(first)
        val shown = mutableListOf<UserMessage>()
        val once = launch { messages.showEach { shown += it } }
        runCurrent()
        once.cancel()
        val again = launch { messages.showEach { shown += it } }
        runCurrent()
        again.cancel()

        assertThat(shown).containsExactly(first)
    }
}

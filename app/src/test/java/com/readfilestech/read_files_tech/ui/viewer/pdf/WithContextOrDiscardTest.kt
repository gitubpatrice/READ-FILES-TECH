package com.readfilestech.read_files_tech.ui.viewer.pdf

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/** A page render its caller, scrolled away, never receives: handed back to be recycled (Claude review R14). */
class WithContextOrDiscardTest {
    @Test
    fun `a result its cancelled caller never receives is discarded`() {
        // A block body: `= runBlocking { … containsExactly(…) }` returns Truth's Ordered, and JUnit 5 skips a
        // test that does not return Unit — without a word (the first sabotage of this test passed unnoticed).
        val discarded = mutableListOf<String>()
        runBlocking { cancelWhileRunning(discarded) }
        assertThat(discarded).containsExactly("render")
    }

    private suspend fun cancelWhileRunning(discarded: MutableList<String>) = coroutineScope {
        val running = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val caller = launch(Dispatchers.Default) {
            withContextOrDiscard<String>(Dispatchers.IO, { discarded.add(it) }) {
                running.complete(Unit)
                // Blocking, as a render in the `:pdf` process: the block itself never sees the cancellation.
                release.await()
                "render"
            }
        }
        running.await()
        caller.cancel()
        release.countDown()
        caller.join()
    }

    @Test
    fun `a result received is the caller's, never discarded`() {
        val discarded = mutableListOf<String>()
        val received = runBlocking { withContextOrDiscard(Dispatchers.IO, { discarded.add(it) }) { "render" } }
        assertThat(received).isEqualTo("render")
        assertThat(discarded).isEmpty()
    }
}

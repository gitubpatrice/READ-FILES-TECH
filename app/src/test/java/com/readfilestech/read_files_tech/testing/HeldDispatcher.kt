package com.readfilestech.read_files_tech.testing

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * An IO dispatcher whose work waits: while [held], each task is kept in [tasks] and the test runs it when
 * and in the order it chooses; otherwise it runs at once, on the caller's thread.
 */
class HeldDispatcher(var held: Boolean = true) : CoroutineDispatcher() {
    val tasks = ArrayDeque<Runnable>()

    override fun isDispatchNeeded(context: CoroutineContext) = held

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks += block
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

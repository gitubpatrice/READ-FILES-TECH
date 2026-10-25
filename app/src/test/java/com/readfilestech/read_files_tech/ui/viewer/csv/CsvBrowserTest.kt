package com.readfilestech.read_files_tech.ui.viewer.csv

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.BitSet

class CsvBrowserTest {
    private val table = runBlocking { CsvTable.of("h\nb\na\nab\n".toByteArray()) }

    /** Searches that take a second, and say which ran and which were cancelled. */
    private class SlowOperations : CsvBrowser.Operations {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override suspend fun matching(query: String): BitSet {
            started += query
            try {
                delay(SEARCH_MS)
            } catch (e: CancellationException) {
                cancelled += query
                throw e
            }
            return BitSet().apply { set(0) }
        }

        override suspend fun order(sort: CsvSortChoice): IntArray = intArrayOf(2, 1, 0)
    }

    /**
     * Typed letter by letter: a letter within 150 ms of the one before starts nothing, and one typed while
     * a search runs cancels it — the 2.15.3 filtered the whole table on the UI thread at each key.
     */
    @Test
    fun `a query typed letter by letter cancels the search before it`() = runTest {
        val operations = SlowOperations()
        val queries = MutableStateFlow("")
        val shown = mutableListOf<CsvShown>()
        val job = launch {
            CsvBrowser(table, StandardTestDispatcher(testScheduler), operations).run(queries, MutableStateFlow(null)) {
                shown += it
            }
        }
        advanceUntilIdle()
        queries.value = "a"
        advanceTimeBy(CsvBrowser.DEBOUNCE_MS + 10)
        assertThat(operations.started).containsExactly("a")
        // Typed before the search of "a" ended, and "ab" within the wait of the next letter.
        queries.value = "ab"
        advanceTimeBy(CsvBrowser.DEBOUNCE_MS - 50)
        queries.value = "abc"
        advanceUntilIdle()
        assertThat(operations.started).containsExactly("a", "abc").inOrder()
        assertThat(operations.cancelled).containsExactly("a")
        assertThat(shown.last().query).isEqualTo("abc")
        assertThat(shown.last().busy).isFalse()
        assertThat(shown.last().view.size).isEqualTo(1)
        job.cancel()
    }

    /** Clearing the field shows every row at once, without the wait for a next letter. */
    @Test
    fun `an empty query shows every row, in the order sorted`() = runTest {
        val operations = SlowOperations()
        val queries = MutableStateFlow("a")
        val sorts = MutableStateFlow<CsvSortChoice?>(null)
        val shown = mutableListOf<CsvShown>()
        val job = launch {
            CsvBrowser(table, StandardTestDispatcher(testScheduler), operations).run(queries, sorts) { shown += it }
        }
        advanceUntilIdle()
        queries.value = ""
        sorts.value = CsvSortChoice(0, ascending = false)
        // No time passes: what is shown is there at once.
        runCurrent()
        val last = shown.last()
        assertThat(last.query).isEmpty()
        assertThat((0 until last.view.size).map(last.view::row)).containsExactly(2, 1, 0).inOrder()
        assertThat(last.sort).isEqualTo(CsvSortChoice(0, ascending = false))
        job.cancel()
    }

    /** A search that runs out of memory on a hostile file says so, and the rows stay: the app does not end. */
    @Test
    fun `a search that fails leaves the rows and says why`() = runTest {
        val failing = object : CsvBrowser.Operations {
            override suspend fun matching(query: String): BitSet = throw OutOfMemoryError("hostile")

            override suspend fun order(sort: CsvSortChoice): IntArray = intArrayOf(0, 1, 2)
        }
        val queries = MutableStateFlow("")
        val shown = mutableListOf<CsvShown>()
        val browser = CsvBrowser(table, StandardTestDispatcher(testScheduler), failing)
        val job = launch { browser.run(queries, MutableStateFlow(null)) { shown += it } }
        advanceUntilIdle()
        queries.value = "b"
        advanceUntilIdle()
        val last = shown.last()
        assertThat(last.failure).isNotNull()
        assertThat(last.busy).isFalse()
        assertThat(last.view.size).isEqualTo(3)
        assertThat(job.isActive).isTrue()
        // The next query clears it.
        queries.value = ""
        advanceUntilIdle()
        assertThat(shown.last().failure).isNull()
        job.cancel()
    }

    @Test
    fun `a sort is saved as one number, and read back`() {
        listOf(CsvSortChoice(0, true), CsvSortChoice(0, false), CsvSortChoice(16_383, false)).forEach {
            assertThat(CsvSortChoice.fromSaved(it.saved)).isEqualTo(it)
        }
        assertThat(CsvSortChoice.fromSaved(CsvSortChoice.NONE)).isNull()
    }

    /** The 2.15.3's `_sort`: a tap sorts up, a second down, a third up again; another column starts up. */
    @Test
    fun `a tap on a column's name sorts up, then down`() {
        val first = CsvSortChoice.tapped(null, 2)
        assertThat(first).isEqualTo(CsvSortChoice(2, ascending = true))
        val second = CsvSortChoice.tapped(first, 2)
        assertThat(second).isEqualTo(CsvSortChoice(2, ascending = false))
        assertThat(CsvSortChoice.tapped(second, 2)).isEqualTo(CsvSortChoice(2, ascending = true))
        assertThat(CsvSortChoice.tapped(second, 0)).isEqualTo(CsvSortChoice(0, ascending = true))
    }

    private companion object {
        const val SEARCH_MS = 1_000L
    }
}

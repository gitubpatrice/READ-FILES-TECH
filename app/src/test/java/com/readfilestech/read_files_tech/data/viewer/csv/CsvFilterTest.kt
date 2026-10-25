package com.readfilestech.read_files_tech.data.viewer.csv

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.BitSet

class CsvFilterTest {
    private fun table(text: String) = runBlocking { CsvTable.of(text.toByteArray()) }

    private fun matching(table: CsvTable, query: String): List<Int> {
        val found = runBlocking { CsvFilter.matching(table, query) }
        return found.stream().toArray().toList()
    }

    @Test
    fun `rows with a cell that contains the query, case ignored, the header never`() {
        val table = table("Nom;Ville\nÉmile;Paris\nalbert;ÉVRY\nZoé;Saint-Étienne\n")
        assertThat(matching(table, "é")).containsExactly(0, 1, 2).inOrder()
        assertThat(matching(table, "PARIS")).containsExactly(0)
        assertThat(matching(table, "ville")).isEmpty()
        assertThat(matching(table, "nowhere")).isEmpty()
    }

    /** The row is matched raw first: what spans two cells, or what a quote changes, is decided cell by cell. */
    @Test
    fun `a match is inside one cell, as the cell shows`() {
        val table = table("a;b\nx;y\n\"say \"\"hi\"\"\";z\n\"x;y\";w\n")
        // Row 0 is `x` then `y`: its raw text holds `x;y`, no cell of it does.
        assertThat(matching(table, "x;y")).containsExactly(2)
        assertThat(matching(table, "say \"hi\"")).containsExactly(1)
        assertThat(matching(table, "\"\"")).isEmpty()
        assertThat(matching(table, "\"hi")).containsExactly(1)
    }

    /** What is not shown is not found: the columns past Excel's limit, a cell past its characters. */
    @Test
    fun `a match past the columns or the characters shown does not count`() {
        val wide = table("h\n" + (0 until FileCaps.CSV_COLUMNS).joinToString(",") { "c" } + ",needle\nneedle\n")
        assertThat(matching(wide, "needle")).containsExactly(1)
        val long = table("h\n" + "a".repeat(FileCaps.CSV_CELL_CHARS) + "needle\n" + "a".repeat(FileCaps.CSV_CELL_CHARS - 6) + "needle\n")
        assertThat(matching(long, "needle")).containsExactly(1)
    }

    /** A row longer than what is lower-cased whole is read cell by cell: found all the same. */
    @Test
    fun `a long row is searched cell by cell`() {
        val table = table("h,i\n" + "x".repeat(100_000) + ",Needle\n")
        assertThat(matching(table, "needle")).containsExactly(0)
    }

    /** A letter typed replaces the search of the one before: it stops at once. */
    @Test
    fun `a search stops when cancelled`() {
        val table = table("n\n" + "abc\n".repeat(10_000))
        val outcome = cancelled { CsvFilter.matching(table, "b") }
        assertThat(outcome.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `the view is the rows matched, in the order sorted`() {
        val matches = BitSet().apply {
            set(1)
            set(3)
        }
        val order = intArrayOf(3, 2, 1, 0)
        fun rows(view: CsvView) = (0 until view.size).map(view::row)
        assertThat(rows(CsvView.of(4, null, null))).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(rows(CsvView.of(4, order, null))).containsExactly(3, 2, 1, 0).inOrder()
        assertThat(rows(CsvView.of(4, null, matches))).containsExactly(1, 3).inOrder()
        assertThat(rows(CsvView.of(4, order, matches))).containsExactly(3, 1).inOrder()
        assertThat(CsvView.of(4, order, BitSet()).size).isEqualTo(0)
    }
}

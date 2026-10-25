package com.readfilestech.read_files_tech.data.viewer.csv

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

class CsvSortTest {
    private fun table(text: String) = runBlocking { CsvTable.of(text.toByteArray()) }

    private fun table(bytes: ByteArray) = runBlocking { CsvTable.of(bytes) }

    /** Column [column] of [table], top to bottom, in the order of the sort. */
    private fun sorted(table: CsvTable, column: Int = 0, ascending: Boolean = true): List<String> = runBlocking {
        val keys = CsvSort.keys(table, column)
        CsvSort.order(table, keys, ascending).map { table.cells(it, column, column + 1, Int.MAX_VALUE).single() }
    }

    /**
     * The 2.15.3's comparator: numbers as numbers when both are, strings otherwise — `9` < `10`, `10` < `1a`,
     * `1a` < `9`: a cycle, no order (`_sort`). Numbers come before text here, which keeps both its pairs of
     * numbers and its pairs of text as they were.
     */
    @Test
    fun `the 2_15_3's cycle becomes an order, numbers before text`() {
        val table = table("v\n1a\n10\n9\n")
        assertThat(sorted(table)).containsExactly("9", "10", "1a").inOrder()
        assertThat(sorted(table, ascending = false)).containsExactly("1a", "10", "9").inOrder()
    }

    @Test
    fun `empty cells first, then numbers by value, then text without regard to case`() {
        val table = table("v;i\nalbert;1\n;2\n-0,5;3\nZoé;4\n2;5\n1e3;6\nZOÉ;7\n0x10;8\n Bob;9\n")
        assertThat(table.separator).isEqualTo(';')
        assertThat(sorted(table)).containsExactly("", "-0,5", "2", "0x10", "1e3", " Bob", "albert", "Zoé", "ZOÉ").inOrder()
    }

    /** In a file of commas, `1,5` can only be quoted, and may be a thousand: text there. */
    @Test
    fun `a decimal comma is a number only where the comma separates no field`() {
        val semicolons = table("n;m\n10,5;a\n9,2;b\n100;c\n")
        assertThat(sorted(semicolons)).containsExactly("9,2", "10,5", "100").inOrder()
        val commas = table("n,m\n\"10,5\",x\n\"9,2\",y\n100,z\n")
        assertThat(sorted(commas)).containsExactly("100", "10,5", "9,2").inOrder()
    }

    /** The 2.15.3's sort was Dart's, which is not stable: rows of equal cells came in any order. */
    @Test
    fun `equal cells keep the file's order, in both directions`() {
        val table = table("k;id\nb;1\na;2\nB;3\nA;4\nb;5\n;6\n;7\n")
        val ids = { ascending: Boolean ->
            runBlocking { CsvSort.order(table, CsvSort.keys(table, 0), ascending) }.map { table.cells(it, 1, 2, 5).single() }
        }
        assertThat(ids(true)).containsExactly("6", "7", "2", "4", "1", "3", "5").inOrder()
        assertThat(ids(false)).containsExactly("1", "3", "5", "2", "4", "6", "7").inOrder()
    }

    /** The text is compared as shown — quotes removed, `""` as one — in whatever encoding the file is. */
    @Test
    fun `text compares as it is shown, in every encoding`() {
        val text = "t\n\"b\"\"\"\nb\n\"a\"\"z\"\n😀\nÉté\nété\néa\n"
        val expected = listOf("a\"z", "b", "b\"", "éa", "Été", "été", "😀")
        val encodings = listOf(
            text.toByteArray(Charsets.UTF_8),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE),
        )
        encodings.forEach { assertThat(sorted(table(it))).isEqualTo(expected) }
        // Windows-1252's € is byte 0x80, U+20AC: after the letters, as its character is.
        val windows = "t\n\"b\"\"\"\nb\n\"a\"\"z\"\nÉté\nété\néa\n€\n"
        assertThat(sorted(table(windows.toByteArray(charset("windows-1252")))))
            .containsExactly("a\"z", "b", "b\"", "éa", "Été", "été", "€").inOrder()
    }

    /** A malformed UTF-8 byte (a BOM vouches for nothing) reads as U+FFFD, as it shows: after the letters. */
    @Test
    fun `malformed UTF-8 sorts as the replacement character it shows`() {
        val replacement = Char(0xFFFD).toString()
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "t\nb\n".toByteArray() +
            byteArrayOf(0xC3.toByte(), 0x0A, 0xE2.toByte(), 0x82.toByte(), 0x0A, 0xF8.toByte(), 0x0A) + "a\n".toByteArray()
        assertThat(sorted(table(bytes))).containsExactly("a", "b", replacement, replacement, replacement).inOrder()
    }

    @Test
    fun `the merge sort is stable, at every size`() {
        val random = Random(42)
        listOf(0, 1, 2, 31, 32, 33, 63, 64, 65, 1000, 4097).forEach { size ->
            val keys = IntArray(size) { random.nextInt(10) }
            val rows = IntArray(size) { it }
            CsvSort.sortStable(rows, EmptyCoroutineContext) { a, b -> keys[a].compareTo(keys[b]) }
            assertThat(rows.toList()).isEqualTo((0 until size).sortedBy { keys[it] })
        }
    }

    /** A new sort, or a search, replaces this one: it stops at once. */
    @Test
    fun `a sort stops when cancelled`() {
        val table = table("n\n" + (0 until 100_000).joinToString("\n") { "${99_999 - it}" })
        val keys = runBlocking { CsvSort.keys(table, 0) }
        val sorting = cancelled { CsvSort.order(table, keys, true) }
        assertThat(sorting.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        val keying = cancelled { CsvSort.keys(table, 0) }
        assertThat(keying.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }
}

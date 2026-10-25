package com.readfilestech.read_files_tech.data.viewer.csv

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.text.CsvRows
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.ByteArrayOutputStream

/**
 * What a CSV at the cap costs on the JVM: the heap the table keeps, and the time to open, sort and
 * filter it. Run by hand — `RFT_MEASURE=1 ./gradlew :app:testDebugUnitTest --tests '*CsvMeasureTest*' -i`
 * — the numbers go to the port's report; the gate does not wait for it. The file is the one
 * `tools/corpus/gen_csv.py` makes for the device (`csv-at-cap-short-cells.csv`).
 */
@EnabledIfEnvironmentVariable(named = "RFT_MEASURE", matches = "1")
class CsvMeasureTest {
    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        repeat(3) { System.gc() }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        println("MEASURE %-40s %8d ms".format(label, (System.nanoTime() - start) / 1_000_000))
        return result
    }

    /**
     * Ten short cells a row, `;`, a decimal comma — as gen_csv.py writes them, up to the cap exactly:
     * about 59 bytes a row, 890 000 rows, under Excel's 1 048 576 (none cut, the whole file kept).
     */
    private fun shortCells(): ByteArray {
        val out = ByteArrayOutputStream(FileCaps.CSV_FILE.toInt())
        out.write((0 until 10).joinToString(";") { "c$it" }.plus("\r\n").toByteArray())
        val rows = (0 until 1000).joinToString("") { i ->
            "${100_000 + i};ab${i % 97};${i % 13},${i % 7};xyzxyz;yz${i % 89};${i * 7};qq;${i % 5};wwwwww;eeeeeeeeeeee\r\n"
        }.toByteArray()
        while (out.size() < FileCaps.CSV_FILE) out.write(rows, 0, minOf(rows.size, FileCaps.CSV_FILE.toInt() - out.size()))
        return out.toByteArray()
    }

    @Test
    fun `a CSV of 50 MiB of short cells`() = runBlocking {
        val before = usedHeap()
        var bytes: ByteArray? = shortCells()
        val table = timed("open (encoding, separator, index)") { CsvTable.of(checkNotNull(bytes)) }
        bytes = null
        // The bytes of the file are the table's own: what it keeps is them and its index.
        val kept = usedHeap() - before
        println(
            "MEASURE rows %d, columns %d, cut %s, heap kept by the table %d MB (file %d MB)"
                .format(table.rows, table.columns, table.rowsCut, kept shr 20, FileCaps.CSV_FILE shr 20),
        )

        for ((column, label) in listOf(0 to "integers", 1 to "text", 2 to "decimal commas")) {
            val keysBefore = usedHeap()
            val keys = timed("keys of column $column ($label)") { CsvSort.keys(table, column) }
            val ascending = timed("sort ascending, column $column") { CsvSort.order(table, keys, true) }
            timed("sort descending, column $column") { CsvSort.order(table, keys, false) }
            println("MEASURE heap of keys + one order, column %d: %d MB".format(column, (usedHeap() - keysBefore) shr 20))
            assertThat(ascending.size).isEqualTo(table.rows)
        }
        for (query in listOf("ab9", "qq", "zzz", "3,4")) {
            val found = timed("filter \"$query\"") { CsvFilter.matching(table, query) }
            println("MEASURE   \"%s\": %d rows".format(query, found.cardinality()))
        }
        assertThat(table.rowsCut).isFalse()
    }

    /** For comparison: the 2.15.3's way, every cell a string in lists of lists — on a tenth of the file. */
    @Test
    fun `the same as lists of strings, on a tenth`() {
        val tenth = String(shortCells(), 0, FileCaps.CSV_FILE.toInt() / 10)
        val before = usedHeap()
        val rows = timed("parse a tenth into lists of strings") { CsvRows.parse(tenth, ';') }
        println("MEASURE lists of strings for 5 MiB: %d MB, %d rows".format((usedHeap() - before) shr 20, rows.size))
    }
}

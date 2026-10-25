package com.readfilestech.read_files_tech.data.viewer.csv

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.BoundedInputStream
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile

class CsvTableTest {
    @TempDir
    lateinit var dir: File

    private fun table(bytes: ByteArray) = runBlocking { CsvTable.of(bytes) }

    private fun table(text: String) = table(text.toByteArray())

    /** A file of the corpus, made by Excel or PowerShell (tools/corpus/csv/corpus.json); the tests run from `app/`. */
    private fun corpus(name: String): CsvTable {
        val file = File("../tools/corpus/csv", name)
        check(file.isFile) { "corpus file missing: ${file.absolutePath}" }
        return runBlocking { CsvTable.read(StorageFileSource(file)) }
    }

    private fun all(table: CsvTable, max: Int = Int.MAX_VALUE): List<List<String>> =
        listOf(table.header(0, table.columns, max)) + (0 until table.rows).map { table.cells(it, 0, table.columns, max) }

    /**
     * What Excel 16 writes in French (COM, xlCSV): Windows-1252, `;`, a decimal comma, CRLF, a cell with a
     * line break, one with `""`, an empty row `;;;;;` — which `csv` 8 skipped, and so does the viewer.
     * The 2.15.3 read it as strict UTF-8 and showed "Fichier CSV vide" (F19).
     */
    @Test
    fun `the CSV Excel writes in French, Windows-1252 or UTF-8`() {
        val expected = listOf(
            listOf("Nom", "Ville", "Montant", "Quantité", "Code postal", "Remarque"),
            listOf("Émile Zola", "Paris", "1234,56", "3", "75001", "Livré"),
            listOf("albert", "Évry", "9,5", "12", "91000", "Il a dit \"bonjour\""),
            listOf("Zoé", "Saint-Étienne", "-0,25", "100", "42000", "Ligne 1\nLigne 2"),
            listOf("Bob", "Lyon; Rhône", "10000000", "7", "01000", "virgule, point-virgule ; fin"),
            listOf("çà€", "Nice", "0", "1", "06000", ""),
        )
        val windows = corpus("excel-csv-1252.csv")
        assertThat(windows.encoding).isEqualTo(TextEncoding.WINDOWS_1252)
        assertThat(windows.separator).isEqualTo(';')
        assertThat(all(windows)).isEqualTo(expected)
        val utf8 = corpus("excel-csv-utf8.csv")
        assertThat(utf8.encoding).isEqualTo(TextEncoding.UTF_8_BOM)
        assertThat(all(utf8)).isEqualTo(expected)
    }

    /** Windows PowerShell 5.1's Export-Csv: every field quoted, a comma file, a UTF-16 one with its #TYPE line. */
    @Test
    fun `the CSV of PowerShell, UTF-8, UTF-16 and Windows-1252`() {
        val comma = corpus("ps-utf8-comma.csv")
        assertThat(comma.separator).isEqualTo(',')
        assertThat(all(comma)).isEqualTo(
            listOf(
                listOf("Nom", "Ville", "Note", "Montant"),
                listOf("Émile Zola", "Paris", "Il a dit \"bonjour\"", "1234,56"),
                listOf("albert", "Évry, Essonne", "Ligne 1\r\nLigne 2", "9,5"),
                listOf("Zoé", "Saint-Étienne", "", "-0,25"),
            ),
        )
        // The #TYPE line is the first row, as Excel shows it too.
        val unicode = corpus("ps-unicode-culture-type.csv")
        assertThat(unicode.encoding).isEqualTo(TextEncoding.UTF_16LE)
        assertThat(unicode.separator).isEqualTo(';')
        assertThat(unicode.header(0, 1, Int.MAX_VALUE)).containsExactly("#TYPE System.Management.Automation.PSCustomObject")
        assertThat(unicode.cells(0, 0, 4, Int.MAX_VALUE)).containsExactly("Nom", "Ville", "Note", "Montant").inOrder()
        assertThat(unicode.cells(2, 0, 4, Int.MAX_VALUE)).containsExactly("albert", "Évry, Essonne", "Ligne 1\r\nLigne 2", "9,5").inOrder()
        val windows = corpus("ps-default-culture.csv")
        assertThat(windows.encoding).isEqualTo(TextEncoding.WINDOWS_1252)
        assertThat(windows.cells(2, 0, 4, Int.MAX_VALUE)).containsExactly("Zoé", "Saint-Étienne", "", "-0,25").inOrder()
    }

    /** Excel's empty sheet is a line break alone; PowerShell's empty file nothing: no row, no column. */
    @Test
    fun `an empty file, a line break alone, rows of empty fields make an empty table`() {
        listOf(corpus("excel-empty.csv"), corpus("ps-new-item-empty.csv"), table(""), table(";;;\r\n;;\n\"\";\"\"\n\n")).forEach {
            assertThat(it.isEmpty).isTrue()
            assertThat(it.rows).isEqualTo(0)
            assertThat(it.columns).isEqualTo(0)
            assertThat(it.header(0, 0, 10)).isEmpty()
        }
        val header = table("a;b;c\r\n")
        assertThat(header.isEmpty).isFalse()
        assertThat(header.rows).isEqualTo(0)
        assertThat(header.columns).isEqualTo(3)
    }

    /** The 2.15.3 took the header's width and dropped the rest of a wider row (`_buildTable`). */
    @Test
    fun `a row wider than the header shows all its cells, a shorter one empty ones`() {
        val table = table("a,b\n1,2,3,4\n5\n")
        assertThat(table.columns).isEqualTo(4)
        assertThat(table.header(0, 4, 10)).containsExactly("a", "b", "", "").inOrder()
        assertThat(table.cells(0, 1, 4, 10)).containsExactly("2", "3", "4").inOrder()
        assertThat(table.cells(1, 0, 4, 10)).containsExactly("5", "", "", "").inOrder()
        assertThat(table.cells(1, 2, 2, 10)).isEmpty()
    }

    /** A row is what is shown of it: a cell of a hostile file is decoded only as far as shown, never cut inside a character. */
    @Test
    fun `cells are cut at the length shown, in every encoding, characters whole`() {
        val cell = "é😀".repeat(50)
        val text = "h\r\n\"$cell\"\r\n"
        val encodings = listOf(
            text.toByteArray(Charsets.UTF_8),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE),
        )
        encodings.forEach { bytes ->
            val table = table(bytes)
            assertThat(table.cells(0, 0, 1, Int.MAX_VALUE)).containsExactly(cell)
            assertThat(table.cells(0, 0, 1, 7).single()).isEqualTo("é😀é😀é")
            assertThat(table.cells(0, 0, 1, 6).single()).isEqualTo("é😀é😀")
            // Five would end on the first half of an emoji: four, then.
            assertThat(table.cells(0, 0, 1, 5).single()).isEqualTo("é😀é")
        }
        val windows = table("h\n${"é".repeat(100)}".toByteArray(charset("windows-1252")))
        assertThat(windows.cells(0, 0, 1, 5).single()).isEqualTo("ééééé")
    }

    /** A UTF-16 file of an odd length: its last half unit is a U+FFFD in the last cell, not dropped. */
    @Test
    fun `the half unit at the end of a UTF-16 file is shown, not dropped`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "a;b\r\n1;2".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0x41)
        val table = table(bytes)
        assertThat(table.cells(0, 0, 2, 10)).containsExactly("1", "2" + Char(0xFFFD)).inOrder()
    }

    /** A quote never closed takes the rest of the file into its cell: still one row, still shown cut. */
    @Test
    fun `a quote never closed is one cell of the rest of the file`() {
        val table = table("a;b\n\"never closed;" + "1;2\n".repeat(100_000))
        assertThat(table.rows).isEqualTo(1)
        assertThat(table.columns).isEqualTo(2)
        assertThat(table.cells(0, 0, 2, 20)).containsExactly("never closed;1;2\n1;2", "").inOrder()
    }

    /** Excel's limits (FileCaps.CSV_ROWS, CSV_COLUMNS): the rest not shown, and said. */
    @Test
    fun `rows and columns past Excel's limits are not shown, and the table says so`() {
        val rows = table("x\n".repeat(FileCaps.CSV_ROWS + 3))
        assertThat(rows.rows).isEqualTo(FileCaps.CSV_ROWS - 1)
        assertThat(rows.rowsCut).isTrue()
        assertThat(rows.cells(FileCaps.CSV_ROWS - 2, 0, 1, 5)).containsExactly("x")
        val exact = table("x\n".repeat(FileCaps.CSV_ROWS) + "\n\n,,\n")
        assertThat(exact.rows).isEqualTo(FileCaps.CSV_ROWS - 1)
        assertThat(exact.rowsCut).isFalse()

        val wide = table((0 until FileCaps.CSV_COLUMNS + 10).joinToString(",") { "c$it" } + "\n1,2\n")
        assertThat(wide.columns).isEqualTo(FileCaps.CSV_COLUMNS)
        assertThat(wide.columnsCut).isTrue()
        assertThat(wide.header(FileCaps.CSV_COLUMNS - 1, FileCaps.CSV_COLUMNS, 10)).containsExactly("c${FileCaps.CSV_COLUMNS - 1}")
        assertThat(table("a,b\n1,2").columnsCut).isFalse()
        // A line of nothing but separators is a row of empty fields: no row.
        assertThat(table(",".repeat(200_000)).isEmpty).isTrue()
    }

    @Test
    fun `a source read through its cap, a file larger than the cap refused, a file that grew too`() {
        val file = File(dir, "a.csv").apply { writeText("a;b\n1;2") }
        assertThat(runBlocking { CsvTable.read(StorageFileSource(file)) }.cells(0, 0, 2, 5)).containsExactly("1", "2").inOrder()

        val big = File(dir, "big.csv")
        RandomAccessFile(big, "rw").use { it.setLength(FileCaps.CSV_FILE + 1) }
        val refused = assertThrows<FileTooLargeException> { runBlocking { CsvTable.read(StorageFileSource(big)) } }
        assertThat(refused.capBytes).isEqualTo(FileCaps.CSV_FILE)

        val growing = object : ViewerSource {
            override val name = "grown.csv"
            override val shareable: File? = null
            override fun length() = 3L
            override fun open(cap: Long) = BoundedInputStream(ByteArrayInputStream(ByteArray(8) { 'a'.code.toByte() }), 4)
            override fun acquireFile(cap: Long) = error("not used")
        }
        assertThrows<FileTooLargeException> { runBlocking { CsvTable.read(growing) } }
    }

    /** The viewer left while a large file was being indexed: the index stops. */
    @Test
    fun `indexing a large file stops when cancelled`() {
        val bytes = "a;b\n".repeat(1_000_000).toByteArray()
        assertThat(cancelled { CsvTable.of(bytes) }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }
}

/**
 * [block] run in a coroutine already cancelled, without suspending first: only the cancellation checks
 * of the code under test can stop it (`withContext` would refuse to start it at all).
 */
internal fun <T> cancelled(block: suspend () -> T): Result<T> {
    var outcome: Result<T>? = null
    runCatching {
        runBlocking {
            coroutineContext.job.cancel()
            outcome = runCatching { block() }
        }
    }
    return checkNotNull(outcome)
}

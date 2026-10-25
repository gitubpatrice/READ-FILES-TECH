package com.readfilestech.read_files_tech.data.viewer.sheet

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.lines
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.open
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.read
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.sheet
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.management.ManagementFactory

/**
 * The .xls read as a stream of records: on workbooks written record by record ([XlsTesting], never by POI),
 * what the files Excel made (SheetCorpusTest) cannot show — a heap bounded by what is shown, the hostile
 * counts of a shared strings table, the records a sheet is made of.
 */
class XlsWorkbookTest {
    @TempDir
    lateinit var dir: File

    private fun xls(name: String, stream: Biff.() -> Unit): File = XlsTesting.ole(File(dir, name), Biff().apply(stream))

    /** A workbook of one sheet, its shared [strings] and the records of its [cells]. */
    private fun oneSheet(strings: List<String>? = null, cells: Biff.() -> Unit): File = xls("one.xls") {
        bof(Biff.GLOBALS).sheet("Feuil1")
        strings?.let(::sst)
        eof().bof(Biff.WORKSHEET).apply(cells).eof()
    }

    /**
     * Four million unique strings near the file's cap (42 MiB), a sheet of 10 000 rows each showing two of
     * them, spread over the whole table: POI's `SSTRecord` held the table whole — 173 bytes a string (293 MiB
     * for the 1.77 million of a real .xls of 45 MiB, measured), 690 MiB here, past the test's heap of 512 MiB.
     * Read with the OLE file and the 5 000 rows shown only.
     */
    @Test
    fun `a table of four million strings near the cap is read with the strings shown only, the sheet to its caps`() {
        val file = xls("strings.xls") {
            bof(Biff.GLOBALS).sheet("Feuil1").sst(STRINGS, STRINGS, STRINGS, ::unique).eof()
            bof(Biff.WORKSHEET)
            for (row in 0 until 2 * SheetLimits().rows) {
                for (column in 0..1) shared(row, column, (row * 2 + column) * SPREAD % STRINGS)
            }
            eof()
        }
        assertThat(file.length()).isLessThan(SheetLimits().fileBytes)
        val before = usedHeap()
        open(file).use { workbook ->
            val sheet = sheet(workbook)
            // Held: the OLE file, whole (POIFSFileSystem from a stream), and the sheet's 10 000 strings.
            assertThat(usedHeap() - before).isLessThan(file.length() + HELD_PAST_THE_FILE)
            assertThat(sheet.rowsCut).isTrue()
            assertThat(sheet.rows).isEqualTo(SheetLimits().rows - 1)
            assertThat(sheet.columns).isEqualTo(2)
            assertThat(sheet.header(0, 2)).containsExactly(unique(0), unique(SPREAD)).inOrder()
            val last = (sheet.rows * 2) * SPREAD
            assertThat(sheet.cells(sheet.rows - 1, 0, 2)).containsExactly(unique(last), unique(last + SPREAD)).inOrder()
        }
    }

    /**
     * POI's `SSTRecord` made an empty string for each one a table declares past those it holds: two billion,
     * from a file of 4 KB. Read here up to its last string: those past it, empty.
     */
    @Test
    fun `a table declaring two billion strings and holding three shows its three`() {
        val file = xls("count.xls") {
            bof(Biff.GLOBALS).sheet("Feuil1").sst(listOf("un", "deux", "trois"), unique = Int.MAX_VALUE, total = Int.MAX_VALUE).eof()
            bof(Biff.WORKSHEET).shared(0, 0, 0).shared(0, 1, 2).shared(0, 2, 1_000_000).number(0, 3, 4.0).eof()
        }
        assertThat(read(file)).containsExactly(listOf("un", "trois", "", "4"))
    }

    @Test
    fun `a shared string past the table's count, or a sheet without its records, is unreadable, the others read`() {
        val file = xls("past.xls") {
            bof(Biff.GLOBALS).sheet("A").sheet("B").sheet("C").sst(listOf("x", "y")).eof()
            bof(Biff.WORKSHEET).shared(0, 0, 2).eof()
            bof(Biff.WORKSHEET).shared(0, 0, 1).eof()
        }
        open(file).use { workbook ->
            assertThat(workbook.sheetNames).containsExactly("A", "B", "C").inOrder()
            assertThrows<UnreadableFileException> { sheet(workbook, 0) }
            assertThat(lines(sheet(workbook, 1))).containsExactly(listOf("y"))
            assertThrows<UnreadableFileException> { sheet(workbook, 2) }
        }
    }

    /**
     * A formula shows its cached result; a text one, the STRING record after it — past the shared formula
     * between them — and nothing when it is missing. A code that names no error shows nothing: POI's
     * `FormulaError.forInt` threw it when the cell was drawn.
     */
    @Test
    fun `formulas show their cached results, a text one from the STRING that follows it`() {
        val file = oneSheet {
            formula(0, 0, Biff.special(0)).sharedFormula().string("texte")
            formula(0, 1, Biff.special(1, 1))
            formula(0, 2, Biff.special(2, 0x07))
            formula(0, 3, 42.5.toRawBits())
            formula(0, 4, Biff.special(0)).number(0, 5, 7.0)
            bool(0, 6, 0x55, error = true)
            label(0, 7, "étiquette")
            bool(0, 8, 0)
        }
        assertThat(read(file)).containsExactly(listOf("texte", "VRAI", "#DIV/0!", "42,5", "", "7", "", "étiquette", "FAUX"))
    }

    /**
     * Sheets counted as POI's `HSSFWorkbook` counted them, a chart sheet included; a chart drawn on a sheet
     * is a substream inside the sheet's, whose records are not the sheet's cells.
     */
    @Test
    fun `a chart sheet counts as a sheet, a chart drawn on a sheet holds none of its cells`() {
        val file = xls("charts.xls") {
            bof(Biff.GLOBALS).sheet("Feuil1").sheet("Graph1").sheet("Feuil3").eof()
            bof(Biff.WORKSHEET).number(0, 1, 1.0).bof(Biff.CHART).number(0, 0, 5.0).eof().eof()
            bof(Biff.CHART).eof()
            bof(Biff.WORKSHEET).label(0, 0, "trois").eof()
        }
        open(file).use { workbook ->
            assertThat(lines(sheet(workbook, 0))).containsExactly(listOf("", "1"))
            assertThat(sheet(workbook, 1).isEmpty).isTrue()
            assertThat(lines(sheet(workbook, 2))).containsExactly(listOf("trois"))
        }
    }

    @Test
    fun `the shared strings a sheet keeps are spent from its text budget`() {
        val file = oneSheet(listOf("abcdef", "ghijkl")) { shared(0, 0, 0).shared(0, 1, 1) }
        assertThrows<FileTooLargeException> { read(file, SheetLimits(textChars = 10)) }
    }

    /**
     * A file of 4 KB whose header says its allocation table addresses 249 MB: `POIFSFileSystem(InputStream)`
     * allocated them at once, before reading a sector — past a phone's heap. Refused before.
     */
    @Test
    fun `an OLE header addressing far more than the cap is refused before it is allocated`() {
        val file = XlsTesting.oleHeaderOnly(File(dir, "header.xls"), fatSectors = 3800)
        val before = allocated()
        assertThrows<UnreadableFileException> { open(file) }
        assertThat(allocated() - before).isLessThan(ALLOCATED_TO_REFUSE)
    }

    /** Excel encrypts a workbook whose structure is protected with its default password: read as the plain one. */
    @Test
    fun `a workbook Excel encrypted with its default password reads as the plain one`() {
        val corpus = File(System.getProperty("user.dir"), "../tools/corpus/sheets")
        val plain = open(File(corpus, "excel-main.xls"))
        val protected = open(File(corpus, "excel-main-protected.xls"))
        plain.use {
            protected.use {
                assertThat(protected.sheetNames).isEqualTo(plain.sheetNames)
                for (index in plain.sheetNames.indices) {
                    assertThat(lines(sheet(protected, index))).isEqualTo(lines(sheet(plain, index)))
                }
            }
        }
    }

    private companion object {
        const val STRINGS = 4_000_000
        const val SPREAD = STRINGS / 10_000
        const val MIB = 1024L * 1024

        /** Held past the OLE file: the sheet's grid and its 10 000 strings, a few MiB. */
        const val HELD_PAST_THE_FILE = 16 * MIB

        /** What refusing a header may allocate: the header, the magic, nothing near the 249 MB. */
        const val ALLOCATED_TO_REFUSE = 16 * MIB

        /** A unique string of eight chars. */
        fun unique(index: Int) = "s" + index.toString().padStart(7, '0')

        fun usedHeap(): Long {
            val runtime = Runtime.getRuntime()
            repeat(3) { System.gc() }
            return runtime.totalMemory() - runtime.freeMemory()
        }

        /** The bytes this thread allocated so far (HotSpot's count). */
        fun allocated(): Long = (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).currentThreadAllocatedBytes
    }
}

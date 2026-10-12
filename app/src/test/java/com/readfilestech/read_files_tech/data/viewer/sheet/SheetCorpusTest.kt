package com.readfilestech.read_files_tech.data.viewer.sheet

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.open
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.sheet
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.io.IOException

/**
 * The spreadsheet viewer on files made by Microsoft Excel (`tools/corpus/sheets/`, its `corpus.json`),
 * against what Excel SHOWS of each cell — `Range.Text`, read through COM when the file was made
 * (`*.expected.json`, the prototype's oracle: 347 of 347 on a phone). The .xls goes through POI, the .xlsx
 * through the app's own reader: both through the one formatter. Every cell must match; within the caps — a
 * cell shows its first chars, a sheet its first columns.
 */
class SheetCorpusTest {
    private val corpus = File(System.getProperty("user.dir"), "../tools/corpus/sheets")

    @TempDir
    lateinit var dir: File

    private fun file(name: String) = File(corpus, name).also { check(it.isFile) { "missing corpus file: $it" } }

    /**
     * One workbook saved by Excel as .xls, .xlsx, strict .xlsx and .ods, and one of the 1904 calendar as .xls
     * and .xlsx: each against what Excel showed before saving it.
     */
    @ParameterizedTest
    @CsvSource(
        "excel-main.xls, excel-main",
        "excel-main.xlsx, excel-main",
        "excel-main-strict.xlsx, excel-main",
        "excel-main.ods, excel-main",
        "excel-1904.xls, excel-1904",
        "excel-1904.xlsx, excel-1904",
    )
    fun `every cell shows what Excel shows`(name: String, made: String) {
        val oracle = JSONObject(file("$made.expected.json").readText())
        val sheets = oracle.getJSONArray("sheets")
        open(file(name)).use { workbook ->
            // Excel's .ods export writes a sheet name's spaces as underscores (`table:name` of its content.xml).
            val names = (0 until sheets.length()).map { sheets.getJSONObject(it).getString("name") }
                .map { if (name.endsWith(".ods")) it.replace(' ', '_') else it }
            assertThat(workbook.sheetNames).containsExactlyElementsIn(names).inOrder()
            val compared = (0 until sheets.length()).map { s ->
                compare(sheet(workbook, s), s, sheets.getJSONObject(s).getJSONArray("cells"))
            }
            val wrong = compared.flatMap { it.second }.map { it.substringBefore(" [") }
            assertThat(wrong).containsExactlyElementsIn(EXPORT_DIFFERENCES[name].orEmpty())
            assertThat(compared.sumOf { it.first }).isAtLeast(if (made == "excel-main") MAIN_CELLS_AT_LEAST else 1)
        }
    }

    /** The cells of [oracle] within the caps compared with [sheet]: how many, and those that differ. */
    private fun compare(sheet: Sheet, index: Int, oracle: JSONArray): Pair<Int, List<String>> {
        val cells = (0 until oracle.length()).map(oracle::getJSONObject).filter { it.getInt("c") < SheetLimits().columns }
        val wrong = cells.mapNotNull { cell ->
            val want = normal(cut(excelText(cell), SheetLimits().cellChars))
            val got = normal(shown(sheet, cell.getInt("r"), cell.getInt("c")))
            "sheet $index ${cell.getInt("r")},${cell.getInt("c")} [${cell.getString("nfl")}]: Excel [$want], shown [$got]".takeIf {
                want != got
            }
        }
        return cells.size to wrong
    }

    @Test
    fun `the row of 256 columns is cut at the cap, the empty sheet is said empty`() {
        open(file("excel-main.xlsx")).use { workbook ->
            assertThat(sheet(workbook, 0).columnsCut).isTrue()
            assertThat(sheet(workbook, 0).columns).isEqualTo(SheetLimits().columns)
            assertThat(sheet(workbook, 2).isEmpty).isTrue()
        }
    }

    /** 10 001 rows: the first 5 000 shown as Excel shows them, the rest said cut. */
    @Test
    fun `of ten thousand rows, the first five thousand show as Excel shows them`() {
        val oracle = JSONObject(file("excel-10k.expected.json").readText()).getJSONArray("sheets").getJSONObject(0).getJSONArray("cells")
        open(file("excel-10k.xlsx")).use { workbook ->
            val sheet = sheet(workbook)
            assertThat(sheet.rowsCut).isTrue()
            assertThat(sheet.rows + 1).isEqualTo(SheetLimits().rows)
            val shown = (0 until oracle.length()).map(oracle::getJSONObject).filter { it.getInt("r") < SheetLimits().rows }
            assertThat(shown).isNotEmpty()
            shown.forEach { cell ->
                assertThat(
                    normal(shown(sheet, cell.getInt("r"), cell.getInt("c")))
                ).isEqualTo(normal(excelText(cell)))
            }
        }
    }

    /** The screen left while a sheet was read: the workbook, released, reads nothing more (POI's would). */
    @Test
    fun `a sheet asked for once the workbook is closed is refused`() {
        val workbook = open(file("excel-main.xls"))
        workbook.close()
        assertThrows<IOException> { sheet(workbook) }
    }

    /**
     * Encrypted by Excel with a password (.xls in RC4, .xlsx as a package inside an OLE file), or saved as
     * Excel 5.0/95: formats not opened, said so. The 2.15.3 called the first unreadable, and showed the last
     * as a sheet named "潲" holding no text.
     */
    @ParameterizedTest
    @ValueSource(strings = ["excel-main-password.xls", "excel-main-password.xlsx", "excel-main-95.xls"])
    fun `an encrypted workbook and an Excel 95 one are formats not opened`(name: String) {
        assertThrows<UnsupportedFormatException> { open(file(name)) }
    }

    @Test
    fun `told by the content, Excel's xlsx under an xls name opens`() {
        val renamed = File(dir, "renamed.xls").also { file("excel-main.xlsx").copyTo(it) }
        open(renamed).use { assertThat(it.sheetNames).hasSize(3) }
    }

    @Test
    fun `an xls cut in half, a record past BIFF8's length, an OLE file of Word, an empty file are unreadable`() {
        val main = file("excel-main.xls").readBytes()
        val cut = File(dir, "cut.xls").apply { writeBytes(main.copyOf(main.size / 2)) }
        assertThrows<UnreadableFileException> { open(cut).use { sheet(it) } }
        // The first BOF record (type 0x0809, 16 bytes, BIFF8) made to declare 65 535 bytes.
        val bof = main.indices.first { i -> BOF.indices.all { main[i + it] == BOF[it] } }
        val damaged = main.copyOf()
        damaged[bof + 2] = -1
        damaged[bof + 3] = -1
        val record = File(dir, "record.xls").apply { writeBytes(damaged) }
        assertThrows<UnreadableFileException> { open(record) }
        val word = File(System.getProperty("user.dir"), "../tools/corpus/documents/word-report.doc")
        assertThrows<UnreadableFileException> { open(word.copyTo(File(dir, "word.xls"))) }
        assertThrows<UnreadableFileException> { open(File(dir, "empty.xls").apply { writeBytes(ByteArray(0)) }) }
    }

    private companion object {
        /**
         * Where a file holds another text than Excel showed, by what saved it: an .ods carries each cell's text
         * as written by the export, and Excel's wrote the negative accounting amount without the fill of its
         * format ("-1 234,56 €" in content.xml, "- 1 234,56 €" on Excel's screen). The viewer shows the file's.
         */
        val EXPORT_DIFFERENCES = mapOf("excel-main.ods" to listOf("sheet 0 11,1"))

        /** Cells of the oracle within the caps: 347 measured by the prototype, the wide row's past 200 aside. */
        const val MAIN_CELLS_AT_LEAST = 200
        val BOF = byteArrayOf(0x09, 0x08, 0x10, 0x00, 0x00, 0x06)

        /** What Excel shows: the value of a text typed in (`Text` may be "####" in a narrow column), else `Text`. */
        fun excelText(cell: JSONObject): String =
            if (cell.getString("type") == "String" && !cell.getBoolean("formula")) cell.optString("value2", "") else cell.getString("text")

        /** Spaces as Excel and Java's locales write them differ (U+00A0, U+202F, U+2007): one space, runs collapsed. */
        fun normal(text: String): String = SPACES.fold(text) { s, c -> s.replace(c, ' ') }.trim().replace(Regex("\\s+"), " ")

        fun shown(sheet: Sheet, row: Int, column: Int): String = when {
            row == 0 -> sheet.header(column, column + 1).single()
            row - 1 < sheet.rows -> sheet.cells(row - 1, column, column + 1).single()
            else -> ""
        }

        val SPACES = listOf(Char(0x00A0), Char(0x202F), Char(0x2007))
    }
}

package com.readfilestech.read_files_tech.data.convert

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * CSV → XLSX (docs D-030): the package's parts, the cells as Excel reads them back, the refusals past
 * Excel's limits, and hostile files. Every workbook written here is also left in `build/oracles/convert/xlsx/`
 * beside its CSV, for `tools/oracles/convert_oracle.py check`: openpyxl and Excel itself open each one and
 * compare every cell with the CSV, read by Python's own `csv` module.
 */
class CsvToXlsxTest {
    private val corpus = File(System.getProperty("user.dir"), "../tools/corpus/csv")
    private val oracle = File(System.getProperty("user.dir"), "build/oracles/convert/xlsx").apply { mkdirs() }

    private suspend fun convert(csv: ByteArray, name: String? = null): Map<String, String> {
        val out = ByteArrayOutputStream()
        CsvToXlsx.write(CsvTable.of(csv), out)
        if (name != null) {
            File(oracle, "$name.csv").writeBytes(csv)
            File(oracle, "$name.xlsx").writeBytes(out.toByteArray())
        }
        return parts(out.toByteArray())
    }

    private fun parts(xlsx: ByteArray): Map<String, String> {
        val parts = linkedMapOf<String, String>()
        ZipInputStream(xlsx.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { parts[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        return parts
    }

    private val sheet = "xl/worksheets/sheet1.xml"

    /** What writing [csv] throws. */
    private suspend inline fun <reified T : Throwable> refusal(csv: ByteArray): T =
        assertThrows<T> { CsvToXlsx.write(CsvTable.of(csv), ByteArrayOutputStream()) }

    @Test
    fun `the package holds the parts Excel needs, and nothing that says who made it or when`() = runTest {
        val parts = convert("a,b\n1,2\n".toByteArray())

        assertThat(parts.keys).containsExactly(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/styles.xml",
            sheet,
        ).inOrder()
        assertThat(parts.keys.none { it.startsWith("docProps") }).isTrue()
    }

    @Test
    fun `numbers are numbers only when they come back unchanged, everything else stays text`() = runTest {
        val csv = "12,-3.5,007,2.50,1e3,0.25,-0,+5, 7,1234567890123456,0612345678\n"
        val xml = convert(csv.toByteArray())[sheet]!!

        assertThat(xml).contains("<c r=\"A1\"><v>12</v></c>")
        assertThat(xml).contains("<c r=\"B1\"><v>-3.5</v></c>")
        assertThat(xml).contains("<c r=\"F1\"><v>0.25</v></c>")
        val texts = listOf(
            "C1" to "007",
            "D1" to "2.50",
            "E1" to "1e3",
            "G1" to "-0",
            "H1" to "+5",
            "I1" to " 7",
            "J1" to "1234567890123456",
            "K1" to "0612345678",
        )
        for ((cell, text) in texts) {
            assertThat(xml).contains("<c r=\"$cell\" t=\"inlineStr\"><is><t xml:space=\"preserve\">$text</t></is></c>")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "1", "-1", "10", "0.5", "-0.5", "123456789012345", "1.23456789012345", "99.01"])
    fun `an exact number`(text: String) {
        assertThat(XlsxCells.isExactNumber(text)).isTrue()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "-", ".5", "5.", "1.0", "-0", "-0.0", "00", "01", "1234567890123456", "1.234567890123456", "1,5", "1 000",
            "１２", "NaN", "Infinity", "0x1F", "1e3", "+1",
        ],
    )
    fun `not an exact number`(text: String) {
        assertThat(XlsxCells.isExactNumber(text)).isFalse()
    }

    @Test
    fun `references run from A1 to XFD1048576`() {
        assertThat(XlsxCells.reference(0, 1)).isEqualTo("A1")
        assertThat(XlsxCells.reference(25, 2)).isEqualTo("Z2")
        assertThat(XlsxCells.reference(26, 3)).isEqualTo("AA3")
        assertThat(XlsxCells.reference(701, 4)).isEqualTo("ZZ4")
        assertThat(XlsxCells.reference(702, 5)).isEqualTo("AAA5")
        assertThat(XlsxCells.reference(XlsxCells.MAX_COLUMNS - 1, XlsxCells.MAX_ROWS)).isEqualTo("XFD1048576")
    }

    @Test
    fun `what XML cannot carry is escaped the way Excel reads it back`() {
        val escaped = StringBuilder().also {
            XlsxCells.escape("a&b<c>d\re\u0001f_x0041_g\tlh😀i" + Char(0xD800) + "j" + Char(0xFFFE), it)
        }.toString()

        assertThat(escaped).isEqualTo("a&amp;b&lt;c&gt;d&#13;e_x0001_f_x005F_x0041_g\tlh😀i_xD800_j_xFFFE_")
    }

    @Test
    fun `a line break and leading spaces in a cell are kept`() = runTest {
        val xml = convert("\"  two\nlines \"\n".toByteArray())[sheet]!!

        assertThat(xml).contains("<t xml:space=\"preserve\">  two\nlines </t>")
    }

    @Test
    fun `an empty field is an empty cell, not written`() = runTest {
        val xml = convert("a,,c\n".toByteArray())[sheet]!!

        assertThat(xml).contains("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\">")
        assertThat(xml).doesNotContain("r=\"B1\"")
        assertThat(xml).contains("<c r=\"C1\" t=\"inlineStr\">")
    }

    @Test
    fun `a CSV without a row is nothing to convert`() = runTest {
        refusal<NothingToConvertException>(ByteArray(0))
        refusal<NothingToConvertException>("\r\n\r\n".toByteArray())
    }

    @Test
    fun `a cell longer than Excel takes is refused, not cut`() = runTest {
        val long = "x".repeat(XlsxCells.MAX_CELL_CHARS + 1)
        val error = refusal<ExcelLimitException>("a,$long\n".toByteArray())

        assertThat(error.limit).isEqualTo(ExcelLimit.CELL_CHARS)
    }

    @Test
    fun `a cell exactly as long as Excel takes is written whole`() = runTest {
        val longest = "y".repeat(XlsxCells.MAX_CELL_CHARS)

        assertThat(convert("$longest\n".toByteArray())[sheet]).contains(">$longest</t>")
    }

    @Test
    fun `more columns than a sheet holds are refused`() = runTest {
        val wide = "x" + ",x".repeat(XlsxCells.MAX_COLUMNS) + "\n"
        val error = refusal<ExcelLimitException>(wide.toByteArray())

        assertThat(error.limit).isEqualTo(ExcelLimit.COLUMNS)
    }

    @Test
    fun `more rows than a sheet holds are refused`() = runTest {
        val tall = "a\n".repeat(XlsxCells.MAX_ROWS + 1).toByteArray()
        val error = refusal<ExcelLimitException>(tall)

        assertThat(error.limit).isEqualTo(ExcelLimit.ROWS)
    }

    @Test
    fun `exactly as many rows as a sheet holds are written, the last one included`() = runTest {
        val tall = "a\n".repeat(XlsxCells.MAX_ROWS).toByteArray()
        val out = ByteArrayOutputStream()
        CsvToXlsx.write(CsvTable.of(tall), out)

        // The sheet's end only: its whole text would be some 70 MB of heap.
        val tail = ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == sheet }
            var last = ByteArray(0)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = zip.read(buffer)
                if (n < 0) break
                val joined = last + buffer.copyOf(n)
                last = joined.copyOfRange(maxOf(0, joined.size - 200), joined.size)
            }
            last.toString(Charsets.UTF_8)
        }
        assertThat(tail).contains("<row r=\"1048576\"><c r=\"A1048576\" t=\"inlineStr\">")
        assertThat(tail).endsWith("</row></sheetData></worksheet>")
    }

    @Test
    fun `progress is reported, the last report being every row`() = runTest {
        val reports = mutableListOf<Int>()
        CsvToXlsx.write(CsvTable.of("n\n".repeat(3000).toByteArray()), ByteArrayOutputStream()) { reports += it }

        assertThat(reports).containsExactly(1024, 2048, 3000).inOrder()
    }

    @Test
    fun `what a progress report throws stops the writing`() = runTest {
        val error = assertThrows<IllegalStateException> {
            CsvToXlsx.write(CsvTable.of("n\n".repeat(3000).toByteArray()), ByteArrayOutputStream()) { error("stopped") }
        }

        assertThat(error).hasMessageThat().isEqualTo("stopped")
    }

    @Test
    fun `the corpus of real CSVs converts, each left for the oracle`() = runTest {
        val files = corpus.listFiles { file -> file.extension == "csv" }!!.sortedBy { it.name }
        assertThat(files).isNotEmpty()
        var converted = 0
        for (file in files) {
            val table = CsvTable.of(file.readBytes())
            if (table.isEmpty) continue
            convert(file.readBytes(), "corpus-" + file.nameWithoutExtension)
            converted++
        }

        // The five with rows: Excel's two, PowerShell's three.
        assertThat(converted).isEqualTo(5)
    }

    @Test
    fun `hostile files make a valid workbook or a refusal, never a broken one`() = runTest {
        val binary = ByteArray(4096) { (it * 31 + 7).toByte() }
        val cut = "é,ü\n".toByteArray().let { it.copyOf(it.size - 2) }
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "a;b\r\n€;œ\r\n".toByteArray(Charsets.UTF_16LE)
        val quotes = "\"unclosed,quote\nnext,row\n".toByteArray()
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13)
        val files = listOf("binary" to binary, "cut-utf8" to cut, "utf16" to utf16, "unclosed-quote" to quotes, "png-named-csv" to png)
        for ((name, bytes) in files) {
            val xml = convert(bytes, "hostile-$name")[sheet]!!
            assertThat(xml).endsWith("</sheetData></worksheet>")
            // Well-formed XML 1.0, control characters of a binary file included: an XML parser takes it.
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        }
    }

    @Test
    fun `French Excel's CSV, in Windows-1252 and semicolons, comes out in its cells`() = runTest {
        val xml = convert("Nom;Prix\r\nCafé;2,50\r\nThé;3\r\n".toByteArray(Charsets.ISO_8859_1), "french-1252")[sheet]!!

        assertThat(xml).contains(">Café</t>")
        // The decimal comma stays text: a choice the CSV does not make for us (it may be a thousands separator).
        assertThat(xml).contains("<c r=\"B2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">2,50</t>")
        assertThat(xml).contains("<c r=\"B3\"><v>3</v></c>")
    }
}

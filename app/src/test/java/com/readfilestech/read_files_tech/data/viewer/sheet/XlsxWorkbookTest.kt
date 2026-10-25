package com.readfilestech.read_files_tech.data.viewer.sheet

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.open
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.read
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.sheet
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The .xlsx reader (docs D-015) on packages written here, small: what Excel's files never hold, and what a
 * hostile one may. Excel's own files are [SheetCorpusTest]'s.
 */
class XlsxWorkbookTest {
    @TempDir
    lateinit var dir: File

    private var made = 0

    private fun xlsx(
        sheet: String,
        strings: String? = null,
        styles: String? = null,
        workbookPr: String = "",
        strict: Boolean = false,
    ): File {
        val main = if (strict) STRICT_MAIN else MAIN
        val rel = if (strict) STRICT_REL else REL
        val sheets = """<sheets><sheet name="F1" sheetId="1" r:id="rId1"/></sheets>"""
        val related = rels(
            "$rel/worksheet" to "worksheets/sheet1.xml",
            "$rel/sharedStrings" to "sharedStrings.xml",
            "$rel/styles" to "/xl/styles.xml"
        )
        val parts = mutableListOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "_rels/.rels" to rels("$rel/officeDocument" to "xl/workbook.xml"),
            "xl/workbook.xml" to """<workbook xmlns="$main" xmlns:r="$rel">$workbookPr$sheets</workbook>""",
            "xl/_rels/workbook.xml.rels" to related,
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$main"><sheetData>$sheet</sheetData></worksheet>""",
        )
        strings?.let { parts += "xl/sharedStrings.xml" to """<sst xmlns="$main">$it</sst>""" }
        styles?.let { parts += "xl/styles.xml" to """<styleSheet xmlns="$main">$it</styleSheet>""" }
        return SheetTesting.zip(File(dir, "test${++made}.xlsx"), *parts.toTypedArray())
    }

    @Test
    fun `shared strings, inline strings, rich runs, escapes, a phonetic reading left out`() {
        val strings = "<si><t>plain</t></si><si><r><t>ri</t></r><r><t>ch</t></r><rPh><t>ふりがな</t></rPh></si><si><t>a_x000A_b</t></si>"
        val sheet = """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c>""" +
            """<c r="D1" t="inlineStr"><is><t>in</t></is></c><c r="E1" t="b"><v>1</v></c><c r="F1" t="e"><v>#N/A</v></c></row>"""
        assertThat(read(xlsx(sheet, strings))).containsExactly(listOf("plain", "rich", "a\nb", "in", "VRAI", "#N/A"))
    }

    /** A formula that gives "" writes an empty string: not content, so not a row to show at the end. */
    @Test
    fun `cells of an empty text do not make a row`() {
        val sheet = """<row r="1"><c r="A1" t="inlineStr"><is><t>a</t></is></c></row>""" +
            """<row r="2"><c r="A2" t="str"><f>""</f><v></v></c></row>"""
        assertThat(read(xlsx(sheet))).containsExactly(listOf("a"))
    }

    @Test
    fun `cells and rows without references follow the one before`() {
        val sheet = """<row><c><v>1</v></c><c r="C1"><v>3</v></c><c><v>4</v></c></row><row><c><v>5</v></c></row>"""
        assertThat(read(xlsx(sheet))).containsExactly(listOf("1", "", "3", "4"), listOf("5")).inOrder()
    }

    @Test
    fun `strict OOXML is read as transitional`() {
        assertThat(
            read(xlsx("""<row r="1"><c r="A1" t="inlineStr"><is><t>strict</t></is></c></row>""", strict = true))
        ).containsExactly(listOf("strict"))
    }

    /** The proof of the stream: past the rows shown, the part is never read — its broken end is never met. */
    @Test
    fun `a sheet is read up to its last row shown, never further`() {
        val rows = (1..10).joinToString("") { """<row r="$it"><c r="A$it"><v>$it</v></c></row>""" } + "<<<< not XML"
        val sheet = open(xlsx(rows), SheetLimits(rows = 5)).use { sheet(it) }
        assertThat(sheet.rows + 1).isEqualTo(5)
        assertThat(sheet.rowsCut).isTrue()
        assertThat(sheet.cells(3, 0, 1)).containsExactly("5")
    }

    @Test
    fun `columns past the cap are cut, and a far cell does not build the grid up to it`() {
        val sheet = open(
            xlsx("""<row r="1"><c r="A1"><v>1</v></c><c r="D1"><v>4</v></c></row><row r="1048576"><c r="XFD1048576"><v>2</v></c></row>"""),
            SheetLimits(columns = 3)
        )
            .use { sheet(it) }
        assertThat(sheet.columns).isEqualTo(1)
        assertThat(sheet.columnsCut).isTrue()
        assertThat(sheet.rowsCut).isTrue()
    }

    @Test
    fun `column letters past any sheet's last column are past the cap, not a negative index`() {
        val sheet = open(xlsx("""<row r="1"><c r="A1"><v>1</v></c><c r="ZZZZZZZZZZZZ1"><v>2</v></c></row>""")).use { sheet(it) }
        assertThat(sheet.columnsCut).isTrue()
    }

    /** Only the strings the sheet shows are kept: a huge string nobody shows spends nothing. */
    @Test
    fun `shared strings the sheet does not show are not kept`() {
        val strings = "<si><t>ok</t></si><si><t>${"x".repeat(1000)}</t></si>"
        assertThat(
            read(xlsx("""<row r="1"><c r="A1" t="s"><v>0</v></c></row>""", strings), SheetLimits(textChars = 100))
        ).containsExactly(listOf("ok"))
    }

    @Test
    fun `a sheet whose texts pass the budget is refused, before it is held`() {
        val cells = (1..20).joinToString("") { """<c t="inlineStr"><is><t>${"y".repeat(10)}</t></is></c>""" }
        assertThrows<FileTooLargeException> { read(xlsx("<row>$cells</row>"), SheetLimits(textChars = 100)) }
    }

    @Test
    fun `a cell keeps what a cell shows of a long text, and spends no more`() {
        val sheet = """<row r="1"><c r="A1" t="s"><v>0</v></c></row>"""
        val limits = SheetLimits(textChars = SheetLimits().cellChars + 10L)
        val line = read(xlsx(sheet, "<si><t>${"z".repeat(300)}</t></si>"), limits).single()
        assertThat(line.single()).hasLength(SheetLimits().cellChars)
    }

    @Test
    fun `a shared string past the table and a value that is not a number are unreadable`() {
        assertThrows<UnreadableFileException> {
            read(xlsx("""<row r="1"><c r="A1" t="s"><v>5</v></c></row>""", "<si><t>a</t></si>"))
        }
        assertThrows<UnreadableFileException> {
            read(xlsx("""<row r="1"><c r="A1"><v>12abc</v></c></row>"""))
        }
    }

    @Test
    fun `a DOCTYPE is refused, a nesting past the depth cap too, and the elements are counted`() {
        val laughs = SheetTesting.zip(
            File(dir, "laughs.xlsx"),
            "_rels/.rels" to rels("$REL/officeDocument" to "xl/workbook.xml"),
            "xl/workbook.xml" to """<!DOCTYPE x [<!ENTITY a "a">]><workbook xmlns="$MAIN"/>""",
        )
        assertThrows<UnreadableFileException> { open(laughs) }
        assertThrows<UnreadableFileException> {
            read(xlsx("""<row r="1"><c r="A1"><v>${"<x>".repeat(300)}"""))
        }
        val many = (1..50).joinToString("") { "<c><v>1</v></c>" }
        assertThrows<TooManyItemsException> { read(xlsx("<row>$many</row>"), SheetLimits(elements = 40)) }
    }

    @Test
    fun `two parts of one name make the package unreadable`() {
        val file = SheetTesting.zip(
            File(dir, "twice.xlsx"),
            "_rels/.rels" to "<x/>",
            "xl/workbook.xml" to "<a/>",
            "XL/Workbook.xml" to "<b/>"
        )
        assertThrows<UnreadableFileException> { open(file) }
    }

    /**
     * The 1904 calendar (Excel for Mac's): serial 0 is 1904-01-01, four years and a day after the 1900 one —
     * in a plain date format and in a format of sections alike, which never goes to CellFormat (D-015).
     */
    @Test
    fun `dates of the 1904 calendar, in a plain format and in a format of sections`() {
        val styles = """<numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy;&quot;neg&quot;;&quot;zero&quot;"/></numFmts>""" +
            """<cellXfs count="2"><xf numFmtId="14"/><xf numFmtId="164"/></cellXfs>"""
        val sheet = """<row r="1"><c r="A1" s="0"><v>44843</v></c><c r="B1" s="1"><v>44843</v></c><c r="C1" s="1"><v>0</v></c></row>"""
        val in1904 = LocalDate.of(1904, 1, 1).plusDays(44843).format(DAY)
        assertThat(
            read(xlsx(sheet, styles = styles, workbookPr = """<workbookPr date1904="1"/>"""))
        ).containsExactly(listOf(in1904, in1904, "zero"))
        val in1900 = LocalDate.of(1899, 12, 30).plusDays(44843).format(DAY)
        assertThat(read(xlsx(sheet, styles = styles))).containsExactly(listOf(in1900, in1900, "zero"))
    }

    /** Longer than Excel allows, a format is never parsed: the number shows as "General". */
    @Test
    fun `a number format longer than Excel allows is shown as General`() {
        val long = "0.00&quot;${"a".repeat(FileCaps.SHEET_FORMAT_CHARS)}&quot;"
        val styles = """<numFmts count="1"><numFmt numFmtId="164" formatCode="$long"/></numFmts>""" +
            """<cellXfs count="1"><xf numFmtId="164"/></cellXfs>"""
        val sheet = """<row r="1"><c r="A1" s="0"><v>1234.5</v></c></row>"""
        assertThat(read(xlsx(sheet, styles = styles))).containsExactly(listOf("1234,5"))
    }

    private companion object {
        const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val STRICT_MAIN = "http://purl.oclc.org/ooxml/spreadsheetml/main"
        const val STRICT_REL = "http://purl.oclc.org/ooxml/officeDocument/relationships"
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

        fun rels(vararg targets: Pair<String, String>): String {
            val each = targets.mapIndexed { i, (type, target) -> """<Relationship Id="rId${i + 1}" Type="$type" Target="$target"/>""" }
            return """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${each.joinToString(
                ""
            )}</Relationships>"""
        }
    }
}

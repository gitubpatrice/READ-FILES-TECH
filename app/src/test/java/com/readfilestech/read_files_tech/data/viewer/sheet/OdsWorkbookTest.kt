package com.readfilestech.read_files_tech.data.viewer.sheet

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.lines
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.open
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.read
import com.readfilestech.read_files_tech.data.viewer.sheet.SheetTesting.sheet
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

/**
 * The 2.15.3's `ods_service_test.dart`, case by case (its 17 tests, under their names), then what the port
 * fixes of it. The two traps of the format it guarded: empty cells are SELF-CLOSING (`<table:table-cell/>`),
 * and ignoring them shifts every following column; repetitions (`table:number-columns-repeated`) are a
 * vector of bombs — a tiny file may declare a billion.
 */
class OdsWorkbookTest {
    @TempDir
    lateinit var dir: File

    private fun ods(body: String, name: String = "test.ods"): File =
        SheetTesting.zip(File(dir, name), "mimetype" to "application/vnd.oasis.opendocument.spreadsheet", "content.xml" to contentXml(body))

    private fun lines(body: String) = read(ods(body))

    @Nested
    inner class `content xml walk` {
        @Test
        fun `reads a sheet, its rows and its columns`() {
            open(ods(table("Feuille1", row(cell("Bonjour") + cell("Monde"))))).use {
                assertThat(it.sheetNames).containsExactly("Feuille1")
                assertThat(lines(sheet(it))).containsExactly(listOf("Bonjour", "Monde"))
            }
        }

        @Test
        fun `several sheets keep the document's order`() {
            open(ods(table("Ventes", row(cell("a"))) + table("Stock", row(cell("b"))))).use {
                assertThat(it.sheetNames).containsExactly("Ventes", "Stock").inOrder()
            }
        }

        /** A Dart map lost the first; a list keeps both, each with its own content. */
        @Test
        fun `two sheets of one name do not overwrite each other`() {
            open(ods(table("Feuille", row(cell("premiere"))) + table("Feuille", row(cell("seconde"))))).use {
                assertThat(it.sheetNames).hasSize(2)
                assertThat(lines(sheet(it, 0))).containsExactly(listOf("premiere"))
                assertThat(lines(sheet(it, 1))).containsExactly(listOf("seconde"))
            }
        }

        @Test
        fun `XML entities are decoded, in the content and in the name`() {
            open(ods("""<table:table table:name="Caf&#233;">${row(cell("accents : &#233;&#224;&#252;"))}</table:table>""")).use {
                assertThat(it.sheetNames).containsExactly("Café")
                assertThat(lines(sheet(it)).single().single()).isEqualTo("accents : éàü")
            }
        }
    }

    @Nested
    inner class `self-closing empty cells` {
        /** THE test of the Dart file: "B" would have slid into column 1, with neither error nor message. */
        @Test
        fun `an empty cell in the middle does not shift the columns`() {
            assertThat(lines(table("F", row(cell("A") + "<table:table-cell/>" + cell("C"))))).containsExactly(listOf("A", "", "C"))
        }

        @Test
        fun `an empty cell at the end of a row is dropped`() {
            assertThat(lines(table("F", row(cell("A") + "<table:table-cell/>")))).containsExactly(listOf("A"))
        }

        @Test
        fun `a wholly empty row at the end is dropped`() {
            assertThat(lines(table("F", row(cell("A")) + row("<table:table-cell/>")))).containsExactly(listOf("A"))
        }
    }

    @Nested
    inner class `repetitions, a vector of bombs` {
        @Test
        fun `a legitimate repetition is unfolded`() {
            assertThat(lines(table("F", row(repeatedCell("3", "x"))))).containsExactly(listOf("x", "x", "x"))
        }

        @Test
        fun `a billion repeated columns do not blow the memory up`() {
            val start = System.nanoTime()
            val sheet = open(ods(table("F", row(repeatedCell("1000000000", "x"))))).use { sheet(it) }
            assertThat((System.nanoTime() - start) / NANOS_PER_MS).isLessThan(1000L)
            assertThat(sheet.columns).isEqualTo(SheetLimits().columns)
            assertThat(sheet.columnsCut).isTrue()
        }

        @Test
        fun `a billion repeated rows do not blow the memory up`() {
            val start = System.nanoTime()
            val sheet = open(
                ods(table("F", """<table:table-row table:number-rows-repeated="1000000000">${cell("x")}</table:table-row>"""))
            ).use { sheet(it) }
            assertThat((System.nanoTime() - start) / NANOS_PER_MS).isLessThan(2000L)
            assertThat(sheet.rows + 1).isEqualTo(SheetLimits().rows)
            assertThat(sheet.rowsCut).isTrue()
        }

        /** LibreOffice ends its sheets with an empty row repeated ~1 048 576 times, to make the grid. */
        @Test
        fun `a repetition of EMPTY rows makes no rows`() {
            val body = table(
                "F",
                row(cell("utile")) + """<table:table-row table:number-rows-repeated="1048576"><table:table-cell/></table:table-row>"""
            )
            assertThat(lines(body)).containsExactly(listOf("utile"))
        }

        @Test
        fun `an absurd or negative repetition counts as 1`() {
            assertThat(lines(table("F", row(repeatedCell("-5", "a") + repeatedCell("nawak", "b"))))).containsExactly(listOf("a", "b"))
        }
    }

    @Nested
    inner class `reading the ZIP` {
        @Test
        fun `a whole ods is read end to end`() {
            assertThat(lines(table("Feuille1", row(cell("Bonjour") + cell("Monde"))))).containsExactly(listOf("Bonjour", "Monde"))
        }

        @Test
        fun `a file that is not a ZIP is refused properly`() {
            val file = File(dir, "text.ods").apply { writeText("ceci n est pas une archive") }
            assertThrows<UnreadableFileException> { open(file) }
        }

        @Test
        fun `a ZIP without content xml is refused properly`() {
            val file = SheetTesting.zip(File(dir, "other.ods"), "mimetype" to "x", "autre.xml" to "<x/>")
            assertThat(assertThrows<UnreadableFileException> { open(file) }).hasMessageThat().contains("no workbook")
        }

        /** "PK" and random bytes: the archive is damaged, which is not the same as a missing part. */
        @Test
        fun `a damaged archive is told apart from missing content`() {
            val file = File(dir, "damaged.ods").apply {
                writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + Random(53).nextBytes(2048))
            }
            assertThat(assertThrows<UnreadableFileException> { open(file) }).hasMessageThat().doesNotContain("no workbook")
        }

        /** The screen then says "This workbook contains no sheet", not an empty table. */
        @Test
        fun `a workbook without a sheet is said, not shown empty`() {
            open(ods("")).use { assertThat(it.sheetNames).isEmpty() }
        }
    }

    /** What the 2.15.3 got wrong (`ods_service.dart:86, 128, 147-148`), fixed. */
    @Nested
    inner class `fixed in the port` {
        @Test
        fun `empty rows repeated between two rows keep the second in its place`() {
            val body = table(
                "F",
                row(cell("1")) + EMPTY_ROWS.replace("RR", "3") + row(cell("5"))
            )
            assertThat(
                lines(body)
            ).containsExactly(listOf("1"), emptyList<String>(), emptyList<String>(), emptyList<String>(), listOf("5")).inOrder()
        }

        @Test
        fun `empty cells repeated between two cells keep the second in its column`() {
            val body = table("F", row(cell("A") + """<table:table-cell table:number-columns-repeated="2"/>""" + cell("D")))
            assertThat(lines(body)).containsExactly(listOf("A", "", "", "D"))
        }

        @Test
        fun `spaces, tabs and line breaks written as elements are kept, a comment is not shown`() {
            val p = "<text:p>a<text:s/>b<text:s text:c=\"2\"/>c<text:tab/>d<text:line-break/>e</text:p>"
            val comment = "<office:annotation><text:p>une note</text:p></office:annotation>"
            val body = table("F", row("<table:table-cell>$comment$p</table:table-cell>"))
            assertThat(lines(body)).containsExactly(listOf("a b  c\td\ne"))
        }

        @Test
        fun `an OpenDocument text under an ods name is not a spreadsheet`() {
            val file = SheetTesting.zip(
                File(dir, "text.ods"),
                "content.xml" to contentXml("").replace("office:spreadsheet>", "office:text>"),
            )
            assertThrows<UnreadableFileException> { open(file) }
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val EMPTY_ROWS = """<table:table-row table:number-rows-repeated="RR"><table:table-cell/></table:table-row>"""

        fun contentXml(body: String) = """<?xml version="1.0" encoding="UTF-8"?><office:document-content """ +
            """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0">""" +
            "<office:body><office:spreadsheet>$body</office:spreadsheet></office:body></office:document-content>"

        fun cell(v: String) = """<table:table-cell office:value-type="string"><text:p>$v</text:p></table:table-cell>"""

        fun repeatedCell(times: String, v: String) =
            """<table:table-cell table:number-columns-repeated="$times" office:value-type="string"><text:p>$v</text:p></table:table-cell>"""

        fun row(cells: String) = "<table:table-row>$cells</table:table-row>"

        fun table(name: String, rows: String) = """<table:table table:name="$name">$rows</table:table>"""
    }
}

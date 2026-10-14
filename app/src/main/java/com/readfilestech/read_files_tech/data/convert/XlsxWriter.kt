package com.readfilestech.read_files_tech.data.convert

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A workbook of ONE sheet, its cells text or numbers, written as it goes (docs D-030): the parts of the
 * package first, then the sheet's rows one after the other into a [ZipOutputStream] — nothing of the
 * sheet is kept, whatever its size. No document properties, no author, no date: a converted CSV says
 * nothing of the phone that converted it.
 *
 * A text cell is an inline string (`t="inlineStr"`): Excel and LibreOffice read it as they read a shared
 * one, and there is no table of every string to hold until the end. A number is written only when
 * [XlsxCells.isExactNumber] says so — its text IS its `<v>`, so Excel reads back the very value written.
 * The 2.15.3 wrote every cell as text (excel_community, `convert_screen.dart:149`).
 */
class XlsxWriter(output: OutputStream) {
    private val zip = ZipOutputStream(output)
    private val xml: Writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8), BUFFER_CHARS)
    private var rows = 0
    private var finished = false

    init {
        part(CONTENT_TYPES_PATH, CONTENT_TYPES)
        part(RELS_PATH, RELS)
        part(WORKBOOK_PATH, WORKBOOK)
        part(WORKBOOK_RELS_PATH, WORKBOOK_RELS)
        part(STYLES_PATH, STYLES)
        zip.putNextEntry(entry(SHEET_PATH))
        xml.write(SHEET_START)
    }

    /** The next row, its cells from column A; an empty text is an empty cell, not written. */
    fun row(cells: List<String>) {
        check(!finished) { "finished" }
        require(rows < XlsxCells.MAX_ROWS) { "past Excel's ${XlsxCells.MAX_ROWS} rows" }
        require(cells.size <= XlsxCells.MAX_COLUMNS) { "${cells.size} cells, past Excel's ${XlsxCells.MAX_COLUMNS}" }
        rows++
        xml.write("<row r=\"$rows\">")
        cells.forEachIndexed { column, text ->
            if (text.isNotEmpty()) cell(XlsxCells.reference(column, rows), text)
        }
        xml.write("</row>")
    }

    private fun cell(reference: String, text: String) {
        require(text.length <= XlsxCells.MAX_CELL_CHARS) { "a cell of ${text.length} characters" }
        if (XlsxCells.isExactNumber(text)) {
            xml.write("<c r=\"$reference\"><v>$text</v></c>")
        } else {
            xml.write("<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
            XlsxCells.escape(text, xml)
            xml.write("</t></is></c>")
        }
    }

    /** Ends the sheet and the package; the output stream is left open. Nothing can be added after. */
    fun finish() {
        if (finished) return
        finished = true
        xml.write(SHEET_END)
        xml.flush()
        zip.closeEntry()
        zip.finish()
    }

    private fun part(path: String, content: String) {
        zip.putNextEntry(entry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** One fixed date for every entry: the ZIP's own timestamps would say when the file was made. */
    private fun entry(path: String) = ZipEntry(path).apply { time = FIXED_TIME }

    private companion object {
        const val BUFFER_CHARS = 64 * 1024

        /** 1980-01-01 00:00, the first date a ZIP entry can hold, in local time as ZIP dates are. */
        val FIXED_TIME: Long = java.util.GregorianCalendar(1980, 0, 1).timeInMillis

        const val CONTENT_TYPES_PATH = "[Content_Types].xml"
        const val RELS_PATH = "_rels/.rels"
        const val WORKBOOK_PATH = "xl/workbook.xml"
        const val WORKBOOK_RELS_PATH = "xl/_rels/workbook.xml.rels"
        const val STYLES_PATH = "xl/styles.xml"
        const val SHEET_PATH = "xl/worksheets/sheet1.xml"

        const val HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val DOC_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val PKG_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
        const val ML = "application/vnd.openxmlformats-officedocument.spreadsheetml"

        const val CONTENT_TYPES = HEAD +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"$ML.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"$ML.worksheet+xml\"/>" +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"$ML.styles+xml\"/>" +
            "</Types>"

        const val RELS = HEAD + "<Relationships xmlns=\"$PKG_RELS\">" +
            "<Relationship Id=\"rId1\" Type=\"$DOC_RELS/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

        /** The sheet named as the 2.15.3 named it (`excel['Sheet1']`). */
        const val WORKBOOK = HEAD + "<workbook xmlns=\"$MAIN\" xmlns:r=\"$DOC_RELS\">" +
            "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

        const val WORKBOOK_RELS = HEAD + "<Relationships xmlns=\"$PKG_RELS\">" +
            "<Relationship Id=\"rId1\" Type=\"$DOC_RELS/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"$DOC_RELS/styles\" Target=\"styles.xml\"/>" +
            "</Relationships>"

        /** The least a stylesheet holds for Excel to open the file without a repair: one of each, by default. */
        const val STYLES = HEAD + "<styleSheet xmlns=\"$MAIN\">" +
            "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill></fills>" +
            "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
            "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
            "</styleSheet>"

        const val SHEET_START = HEAD + "<worksheet xmlns=\"$MAIN\"><sheetData>"
        const val SHEET_END = "</sheetData></worksheet>"
    }
}

/** What a cell of [XlsxWriter] may be, and how it is written: Excel's own limits and conventions. */
object XlsxCells {
    /** Excel's rows and columns in a sheet; the CSV viewer's caps are the same (`FileCaps.CSV_ROWS`, `CSV_COLUMNS`). */
    const val MAX_ROWS = 1_048_576
    const val MAX_COLUMNS = 16_384

    /** Characters Excel keeps in a cell. */
    const val MAX_CELL_CHARS = 32_767

    /** The digits a double holds exactly, and Excel shows: a longer number would come back changed. */
    const val MAX_DIGITS = 15

    private const val LETTERS = 26
    private const val FIRST_UNSAFE = 0x20
    private const val TAB = 0x09
    private const val LF = 0x0A
    private const val CR = 0x0D
    private const val HEX_DIGITS = 4

    /** U+FFFE and U+FFFF, which XML excludes. */
    private const val NOT_CHARACTERS = 0xFFFE

    /**
     * Whether [text] is written as a number: plain decimal digits, an optional minus and point, no
     * leading zero (`007` is an identifier), no trailing zero after the point (`2.50` would come back
     * `2.5`), not `-0`, at most [MAX_DIGITS] digits — so the number Excel reads is the text, digit for
     * digit. Everything else stays text as it is: `1e3`, `+5`, ` 5`, `1 234`, `1,5`, `0x1F`, `12.`.
     */
    fun isExactNumber(text: String): Boolean {
        val body = if (text.startsWith('-')) text.substring(1) else text
        val point = body.indexOf('.')
        val integer = if (point < 0) body else body.substring(0, point)
        val fraction = if (point < 0) "" else body.substring(point + 1)
        val digitsOnly = integer.isNotEmpty() && (integer + fraction).all { it in '0'..'9' }
        val noLeadingZero = integer == "0" || !integer.startsWith('0')
        val noTrailingZero = point < 0 || (fraction.isNotEmpty() && !fraction.endsWith('0'))
        val negativeZero = body != text && integer == "0" && point < 0
        return digitsOnly && noLeadingZero && noTrailingZero && !negativeZero && integer.length + fraction.length <= MAX_DIGITS
    }

    /** `A1`-style reference of [column] (0 is A) and [row] (1 is the first). */
    fun reference(column: Int, row: Int): String {
        val letters = StringBuilder()
        var n = column + 1
        while (n > 0) {
            val rest = (n - 1) % LETTERS
            letters.append('A' + rest)
            n = (n - 1) / LETTERS
        }
        return letters.reverse().append(row).toString()
    }

    /**
     * [text] as the content of a `<t>`: `&`, `<`, `>` escaped; a carriage return as a character reference
     * (written as it is, an XML reader would turn it into a line feed); and as `_xHHHH_` — OOXML's escape
     * (ECMA-376 ST_Xstring), which Excel decodes — what XML 1.0 cannot carry at all: the other control
     * characters, U+FFFE and U+FFFF, a lone surrogate. A `_x` of the text itself that reads as an escape has
     * its `_` escaped, so it comes back as written.
     */
    fun escape(text: String, out: Appendable) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '&' -> out.append("&amp;")
                c == '<' -> out.append("&lt;")
                c == '>' -> out.append("&gt;")
                c.code == CR -> out.append("&#13;")
                c == '_' && looksEscaped(text, i) -> out.append("_x005F_")
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    out.append(c).append(text[i + 1])
                    i++
                }
                needsEscape(c) -> out.append("_x").append(hex(c.code)).append('_')
                else -> out.append(c)
            }
            i++
        }
    }

    private fun needsEscape(c: Char): Boolean =
        (c.code < FIRST_UNSAFE && c.code != TAB && c.code != LF) || c.code >= NOT_CHARACTERS || Character.isSurrogate(c)

    /**
     * `_xHHHH_` at [at] once written: four hex digits between `_x` and `_` — or a character after them that is
     * itself written `_xHHHH_`, whose escape brings the `_` (Claude review R18: `_xABCD` then U+0001 read
     * back as `ꯍx0001_`). Escaping a `_` that did not need it is always safe.
     */
    private fun looksEscaped(text: String, at: Int): Boolean {
        val end = at + 2 + HEX_DIGITS
        return end < text.length && text[at + 1] == 'x' && (text[end] == '_' || needsEscape(text[end])) &&
            (at + 2 until end).all { text[it] in '0'..'9' || text[it] in 'a'..'f' || text[it] in 'A'..'F' }
    }

    private fun hex(code: Int): String = code.toString(HEX_RADIX).uppercase().padStart(HEX_DIGITS, '0')

    private const val HEX_RADIX = 16
}

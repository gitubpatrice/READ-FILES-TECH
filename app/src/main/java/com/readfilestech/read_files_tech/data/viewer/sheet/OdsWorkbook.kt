package com.readfilestech.read_files_tech.data.viewer.sheet

import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.document.XmlEvents
import com.readfilestech.read_files_tech.data.viewer.zip.ZipItem
import org.xmlpull.v1.XmlPullParser

/**
 * An OpenDocument spreadsheet (.ods): its `content.xml`, read as the 2.15.3 read it (`ods_service.dart`) —
 * each `table:table` a sheet, in the document's order; a cell's text the paragraphs it shows (the formatted
 * value, "42 %", not `office:value`), joined by line breaks. A sheet is read when shown, the archive being
 * read again up to its end: one sheet is held at a time.
 *
 * What the 2.15.3 got wrong, fixed:
 * - a run of EMPTY rows or cells (`table:number-rows-repeated`, `-columns-repeated`) was folded to one
 *   wherever it was (`ods_service.dart:86, 128`): the rows under it moved up, the cells right of it moved
 *   left. Only the empty ones at the END of a sheet or a row are dropped now; those between are kept;
 * - `<text:s/>` (the spaces LibreOffice writes as an element), `<text:tab/>` and `<text:line-break/>` were
 *   erased with the other tags (`:148`), gluing words; and a cell's comment (`office:annotation`, its own
 *   paragraphs) showed as the cell's text (`:147`);
 * - the whole `content.xml`, up to 200 MiB, was inflated and decoded into one String (`:211, 216`).
 */
internal class OdsWorkbook private constructor(
    private val parts: SheetParts,
    private val content: ZipItem,
    private val limits: SheetLimits,
    override val sheetNames: List<String>,
    private val budget: TextBudget,
) : Workbook() {
    override suspend fun read(index: Int): Sheet = parts.parse(content, limits, whole = true) { xml ->
        requireNotNull(Content(xml, limits, budget.forSheet()).read(target = index)) { "no sheet $index" }
    }

    override fun release() = parts.archive.close()

    companion object {
        const val CONTENT = "content.xml"

        suspend fun open(parts: SheetParts, limits: SheetLimits): OdsWorkbook {
            val content = requireNotNull(parts[CONTENT])
            val budget = TextBudget(limits.textChars)
            val names = ArrayList<String>()
            parts.parse(content, limits, whole = true) { Content(it, limits, budget, names).read(target = NONE) }
            return OdsWorkbook(parts, content, limits, names, budget)
        }

        private const val NONE = -1
    }
}

private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
private const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"

/**
 * The document's body: the name of each sheet into [names] (when given), and sheet [target] read — the
 * read stops at its end. Not a spreadsheet (an .odt under an .ods name): unreadable.
 */
private class Content(
    private val xml: XmlEvents,
    private val limits: SheetLimits,
    private val budget: TextBudget,
    private val names: MutableList<String>? = null,
) {
    private var spreadsheet = false
    private var index = 0

    suspend fun read(target: Int): Sheet? {
        xml.requireRoot(setOf(OFFICE), "document-content")
        var sheet: Sheet? = null
        var type = xml.next()
        while (sheet == null && type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) sheet = start(target)
            type = xml.next()
        }
        if (!spreadsheet) throw UnreadableFileException("an OpenDocument that is not a spreadsheet")
        return sheet
    }

    /** At a START_TAG: the sheet, when it is the table [target]; null otherwise. */
    private suspend fun start(target: Int): Sheet? {
        if (xml.namespace == OFFICE && xml.name == "spreadsheet") spreadsheet = true
        if (xml.namespace != TABLE || xml.name != "table") return null
        names?.add(cut(xml.attribute(TABLE, "name").orEmpty(), limits.cellChars).also { budget.spend(it.length) })
        val sheet = if (index++ == target) Table(xml, limits, budget).read() else null
        if (sheet == null) xml.skip()
        return sheet
    }
}

/** One `table:table`, from its START_TAG to its END_TAG. */
private class Table(private val xml: XmlEvents, private val limits: SheetLimits, budget: TextBudget) {
    private val grid = GridBuilder(limits, budget)

    /** The next row's index: a Long, as repetitions add up past any Int. */
    private var row = 0L

    suspend fun read(): Sheet {
        val depth = xml.depth
        var type = xml.next()
        while (type != XmlPullParser.END_TAG || xml.depth != depth) {
            when {
                type == XmlPullParser.END_DOCUMENT -> throw UnreadableFileException("a table never closed")
                type != XmlPullParser.START_TAG -> Unit
                xml.namespace == TABLE && xml.name == "table-row" -> row()
                // Groups of rows: their rows are the sheet's.
                xml.namespace != TABLE || xml.name !in ROW_GROUPS -> xml.skip()
            }
            type = xml.next()
        }
        return grid.build { _, _ -> "" }
    }

    /** A row, repeated as it says: empty, it only moves the next row down. */
    private suspend fun row() {
        val repeat = repeatCount(xml.attribute(TABLE, "number-rows-repeated"))
        val cells = cells()
        var times = 0L
        while (cells.isNotEmpty() && times < repeat && grid.keeps(clamp(row + times), 0)) {
            // Each text once in the budget: a repetition holds the same String.
            cells.forEach { (column, text) -> grid.text(clamp(row + times), column, text, counted = times == 0L) }
            times++
        }
        row += repeat
    }

    /** The cells of the row, by column, those with a text only; a cell past the column cap cuts the sheet. */
    private suspend fun cells(): List<Pair<Int, String>> {
        val depth = xml.depth
        val cells = ArrayList<Pair<Int, String>>()
        var column = 0L
        var type = xml.next()
        while (type != XmlPullParser.END_TAG || xml.depth != depth) {
            when {
                type == XmlPullParser.END_DOCUMENT -> throw UnreadableFileException("a row never closed")
                type != XmlPullParser.START_TAG -> Unit
                xml.namespace == TABLE && xml.name in CELLS -> column = cell(column, cells)
                else -> xml.skip()
            }
            type = xml.next()
        }
        return cells
    }

    /** The cell at [column], its text added to [cells] as many times as it repeats; the next cell's column. */
    private suspend fun cell(column: Long, cells: MutableList<Pair<Int, String>>): Long {
        val repeat = repeatCount(xml.attribute(TABLE, "number-columns-repeated"))
        val text = cellText()
        var times = 0L
        while (text.isNotEmpty() && times < repeat && grid.keeps(clamp(row), clamp(column + times))) {
            cells += (column + times).toInt() to text
            times++
        }
        return column + repeat
    }

    /** A cell's paragraphs, from its START_TAG to its END_TAG. */
    private suspend fun cellText(): String {
        val depth = xml.depth
        val text = Paragraphs(limits.cellChars)
        var type = xml.next()
        while (type != XmlPullParser.END_TAG || xml.depth != depth) {
            when {
                type == XmlPullParser.END_DOCUMENT -> throw UnreadableFileException("a cell never closed")
                type == XmlPullParser.TEXT -> text.text(xml.text)
                type == XmlPullParser.END_TAG -> if (xml.namespace == TEXT) text.end(xml.name)
                type != XmlPullParser.START_TAG -> Unit
                xml.namespace == OFFICE && xml.name == "annotation" -> xml.skip()
                xml.namespace == TEXT -> text.start(xml.name, xml.attribute(TEXT, "c"))
            }
            type = xml.next()
        }
        return text.shown()
    }

    private companion object {
        val CELLS = setOf("table-cell", "covered-table-cell")
        val ROW_GROUPS = setOf("table-header-rows", "table-row-group", "table-rows")

        /**
         * `table:number-…-repeated`, a vector of bombs rather than a detail of the format: a file of a few
         * kilobytes may declare a billion. Read as a Long, a value that is not a count is 1; the loops that
         * unfold it stop at the caps.
         */
        fun repeatCount(raw: String?): Long = raw?.toLongOrNull()?.takeIf { it >= 1 } ?: 1

        fun clamp(index: Long): Int = minOf(index, Int.MAX_VALUE.toLong()).toInt()
    }
}

/**
 * The paragraphs of a cell (`text:p`, `text:h`), joined by line breaks and trimmed, as the 2.15.3 joined them;
 * inside them `<text:s text:c="n"/>` is n spaces, `<text:tab/>` a tab, `<text:line-break/>` a line break.
 * Gathered as far as a cell shows, and a char more: the cut, made later, then knows there was more.
 */
private class Paragraphs(private val cellChars: Int) {
    private val out = StringBuilder()
    private var seen = 0
    private var open = 0

    fun start(name: String, count: String?) {
        when {
            name == "p" || name == "h" -> if (open++ == 0 && seen++ > 0) append("\n")
            open == 0 -> Unit
            name == "s" -> append(" ".repeat((count?.toIntOrNull() ?: 1).coerceIn(1, cellChars + 1)))
            name == "tab" -> append("\t")
            name == "line-break" -> append("\n")
        }
    }

    fun end(name: String) {
        if (name == "p" || name == "h") open--
    }

    fun text(text: String) {
        if (open > 0) append(text)
    }

    fun shown(): String = cut(out.toString().trim(), cellChars)

    private fun append(s: String) {
        val room = cellChars + 1 - out.length
        if (room > 0) out.append(s, 0, minOf(s.length, room))
    }
}

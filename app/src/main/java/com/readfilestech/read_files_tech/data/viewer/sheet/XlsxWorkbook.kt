package com.readfilestech.read_files_tech.data.viewer.sheet

import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.document.XmlEvents
import com.readfilestech.read_files_tech.data.viewer.zip.ZipItem
import org.apache.poi.ss.usermodel.BuiltinFormats
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * An .xlsx, read by the app's own reader (docs D-015): ZIP and XML pull parser, without poi-ooxml (12 MB of
 * jars, StAX absent from Android, `java.awt` through `XSSFColor`). Its relationships, `workbook.xml`,
 * `styles.xml` (number formats) and `sharedStrings.xml` are read whole, within the part cap; a sheet is
 * read AS A STREAM up to its last row shown, never inflated whole — a 50 MB file holds sheets of hundreds
 * of MB. Shown: the values cached in the file (nothing is computed), formatted by [CellText], the engine
 * of the .xls. Transitional and strict OOXML alike.
 *
 * What the 2.15.3 got wrong (excel_community 2.2.1), fixed: a formula showed its text (`SUM(A1:A3)`) and
 * not its value, numbers had no format (`19.990000000000002`), dates were ISO and in UTC (inventory K3);
 * every sheet was decoded whole before the 5 000 rows were taken, so one far cell (XFD1048576) was enough
 * to run out of memory (D21).
 */
internal class XlsxWorkbook private constructor(
    private val parts: SheetParts,
    private val limits: SheetLimits,
    private val sheets: List<Pair<String, ZipItem?>>,
    private val sharedStrings: ZipItem?,
    private val numbers: Numbers,
    private val budget: TextBudget,
) : Workbook() {
    override val sheetNames: List<String> = sheets.map { it.first }

    override suspend fun read(index: Int): Sheet {
        val budget = budget.forSheet()
        val grid = GridBuilder(limits, budget)
        // A chart sheet, or a sheet whose part is missing: an empty one, as Excel shows a chart's cells.
        sheets[index].second?.let { part ->
            parts.parse(part, limits, whole = false) { SheetReader(it, grid, numbers.cellText, limits.cellChars).read() }
        }
        val indices = grid.sharedIndices()
        if (indices.isNotEmpty()) {
            val part = sharedStrings ?: throw UnreadableFileException("shared strings used, and none in the package")
            val strings = parts.parse(part, limits, whole = true) { SharedStrings(it, indices, limits.cellChars, budget).read() }
            grid.resolveShared { strings[indices.binarySearch(it)] }
        }
        return grid.build(numbers::format)
    }

    override fun release() = parts.archive.close()

    /**
     * The numbers of the workbook as shown: the format of each cell style (`cellXfs`, a built-in index or a
     * custom code), the calendar of its dates, the formatter they share — used under its lock.
     */
    class Numbers(
        private val styles: IntArray,
        private val custom: Map<Int, String>,
        private val date1904: Boolean,
        val cellText: CellText
    ) {
        fun format(value: Double, style: Int): String {
            val index = styles.getOrElse(style) { 0 }
            val code = custom[index] ?: BuiltinFormats.getBuiltinFormat(index) ?: GENERAL
            return synchronized(cellText) { cellText.formatNumber(value, index, code, date1904) }
        }
    }

    companion object {
        const val PACKAGE_RELATIONSHIPS = "_rels/.rels"
        private const val GENERAL = "General"
        private const val OFFICE_DOCUMENT = "/officeDocument"
        private const val WORKSHEET = "/worksheet"
        private const val SHARED_STRINGS = "/sharedStrings"
        private const val STYLES = "/styles"

        suspend fun open(parts: SheetParts, limits: SheetLimits, locale: Locale): XlsxWorkbook {
            val budget = TextBudget(limits.textChars)
            val root = relationships(parts, limits, PACKAGE_RELATIONSHIPS, "")
            val workbookName = root.firstOrNull { it.type.endsWith(OFFICE_DOCUMENT) }?.target ?: "xl/workbook.xml"
            val workbook = parts[workbookName] ?: throw UnreadableFileException("a package without its workbook")
            val folder = workbookName.substringBeforeLast('/', "")
            val related = relationships(parts, limits, "$folder/_rels/${workbookName.substringAfterLast('/')}.rels", folder)
            val declared = parts.parse(workbook, limits, whole = true) {
                WorkbookPart(it, limits.cellChars, budget).apply { read() }
            }
            val byId = related.associateBy { it.id }
            val sheets = declared.sheets.map { (name, id) ->
                name to byId[id]?.takeIf { it.type.endsWith(WORKSHEET) }?.let { parts[it.target] }
            }
            val styles = related.firstOrNull { it.type.endsWith(STYLES) }?.let { parts[it.target] }
                ?.let { part -> parts.parse(part, limits, whole = true) { StylesPart(it, budget).apply { read() } } }
            val numbers = Numbers(styles?.styles ?: IntArray(0), styles?.custom.orEmpty(), declared.date1904, CellText(locale))
            val strings = related.firstOrNull { it.type.endsWith(SHARED_STRINGS) }?.let { parts[it.target] }
            return XlsxWorkbook(parts, limits, sheets, strings, numbers, budget)
        }

        private class Relationship(val type: String, val target: String, val id: String)

        /** The relationships of [name] (none when it is absent), their targets resolved from [folder]. */
        private suspend fun relationships(parts: SheetParts, limits: SheetLimits, name: String, folder: String): List<Relationship> =
            parts[name.trimStart('/')]?.let { part ->
                parts.parse(part, limits, whole = true) { xml ->
                    val out = ArrayList<Relationship>()
                    var type = xml.next()
                    while (type != XmlPullParser.END_DOCUMENT) {
                        val target = xml.takeIf { type == XmlPullParser.START_TAG && it.name == "Relationship" }
                            ?.takeIf { it.attribute(null, "TargetMode") != "External" }
                            ?.attribute(null, "Target")
                        if (target != null) {
                            val kind = xml.attribute(null, "Type").orEmpty()
                            out += Relationship(kind, resolve(folder, target), xml.attribute(null, "Id").orEmpty())
                        }
                        type = xml.next()
                    }
                    out
                }
            }.orEmpty()

        /** [target] from [folder]: `..` and `.` resolved, an absolute target from the package's root. */
        private fun resolve(folder: String, target: String): String {
            if (target.startsWith("/")) return target.trimStart('/')
            val path = folder.split('/').filter(String::isNotEmpty).toMutableList()
            for (segment in target.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> path.removeLastOrNull()
                    else -> path += segment
                }
            }
            return path.joinToString("/")
        }
    }
}

/**
 * `workbook.xml`: each sheet's name and relationship id (`r:id`, transitional or strict), in order, and the
 * calendar of its dates. Elements are told by their local name, in either namespace.
 */
private class WorkbookPart(private val xml: XmlEvents, private val nameChars: Int, private val budget: TextBudget) {
    val sheets = ArrayList<Pair<String, String?>>()
    var date1904 = false
        private set

    suspend fun read() {
        var type = xml.next()
        while (type != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) start()
            type = xml.next()
        }
    }

    private fun start() {
        when (xml.name) {
            "workbookPr" -> date1904 = xml.attribute(null, "date1904").let { it == "1" || it == "true" }
            "sheet" -> {
                val name = cut(xml.attribute(null, "name").orEmpty(), nameChars)
                budget.spend(name.length)
                sheets += name to RELATIONSHIP_IDS.firstNotNullOfOrNull { xml.attribute(it, "id") }
            }
        }
    }

    private companion object {
        val RELATIONSHIP_IDS = listOf(
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "http://purl.oclc.org/ooxml/officeDocument/relationships",
        )
    }
}

/** `styles.xml`: the custom number formats, and the format of each cell style (`cellXfs`). */
private class StylesPart(private val xml: XmlEvents, private val budget: TextBudget) {
    val custom = HashMap<Int, String>()
    var styles = IntArray(0)
        private set

    private var all = IntArray(INITIAL_STYLES)
    private var count = 0
    private var inCellStyles = false

    suspend fun read() {
        var type = xml.next()
        while (type != XmlPullParser.END_DOCUMENT) {
            when (type) {
                XmlPullParser.START_TAG -> start()
                XmlPullParser.END_TAG -> if (xml.name == "cellXfs") inCellStyles = false
            }
            type = xml.next()
        }
        styles = all.copyOf(count)
    }

    private fun start() {
        when (xml.name) {
            "numFmt" -> numberFormat()
            "cellXfs" -> inCellStyles = true
            "xf" -> if (inCellStyles) style(xml.attribute(null, "numFmtId")?.toIntOrNull() ?: 0)
        }
    }

    private fun style(format: Int) {
        if (count == all.size) all = all.copyOf(count * 2)
        all[count++] = format
    }

    /** A custom format, spent from the budget; [CellText] shows one longer than Excel allows as "General". */
    private fun numberFormat() {
        val id = xml.attribute(null, "numFmtId")?.toIntOrNull()
        val code = xml.attribute(null, "formatCode")
        if (id != null && code != null) {
            budget.spend(code.length)
            custom[id] = code
        }
    }

    private companion object {
        const val INITIAL_STYLES = 64
    }
}

/**
 * `sharedStrings.xml`: the text of each `<si>` whose index is in [wanted] (sorted), its runs joined, its
 * phonetic reading (`rPh`) left out — only the strings the sheet shows are kept, and the read stops at
 * the last of them.
 */
private class SharedStrings(private val xml: XmlEvents, private val wanted: IntArray, cellChars: Int, private val budget: TextBudget) {
    private val found = arrayOfNulls<String>(wanted.size)
    private val text = CellChars(cellChars)
    private var index = -1
    private var next = 0

    suspend fun read(): Array<String> {
        while (next < wanted.size) {
            when (xml.next()) {
                XmlPullParser.END_DOCUMENT -> throw UnreadableFileException("a shared string past the table: ${wanted[next]}")
                XmlPullParser.START_TAG -> start()
                XmlPullParser.END_TAG -> if (xml.name == "si" && index == wanted[next]) {
                    found[next++] = text.shown().also {
                        budget.spend(it.length)
                    }
                }
            }
        }
        return Array(found.size) { found[it].orEmpty() }
    }

    private suspend fun start() {
        when (xml.name) {
            "si" -> {
                index++
                text.clear()
            }
            "rPh" -> xml.skip()
            "t" -> if (index == wanted[next]) text.collect(xml)
        }
    }
}

/**
 * The text of a cell as it is read, kept within what a cell shows: OOXML's `_xHHHH_` escapes (line breaks,
 * control chars) take up to 7 chars for one, so that many more are gathered before they are decoded.
 */
internal class CellChars(private val max: Int) {
    private val raw = StringBuilder()

    fun clear() = raw.setLength(0)

    /** The text of the element [xml] is at, up to its END_TAG. */
    suspend fun collect(xml: XmlEvents) {
        val depth = xml.depth
        var type = xml.next()
        while (type != XmlPullParser.END_TAG || xml.depth != depth) {
            when (type) {
                XmlPullParser.TEXT -> append(xml.text)
                XmlPullParser.START_TAG -> xml.skip()
                XmlPullParser.END_DOCUMENT -> throw UnreadableFileException("an element never closed")
            }
            type = xml.next()
        }
    }

    private fun append(text: String) {
        val room = max * ESCAPE_CHARS - raw.length
        if (room > 0) raw.append(text, 0, minOf(text.length, room))
    }

    fun shown(): String = cut(unescape(raw.toString()), max)

    private companion object {
        const val ESCAPE_CHARS = 7
        const val HEX = 16
        val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

        fun unescape(s: String): String = if (!s.contains(
                "_x"
            )
        ) {
            s
        } else {
            ESCAPE.replace(s) { it.groupValues[1].toInt(HEX).toChar().toString() }
        }
    }
}

/**
 * One sheet, read as a stream: each row's cells kept within the caps, until the first row past the row cap
 * — the rest of the part is never inflated. `<f>` (a formula's text) is skipped: its cached `<v>` shows.
 */
private class SheetReader(private val xml: XmlEvents, private val grid: GridBuilder, private val cellText: CellText, cellChars: Int) {
    private var row = -1
    private var column = -1
    private var type: String? = null
    private var style = 0
    private val value = CellChars(cellChars)
    private val inline = CellChars(cellChars)

    /** Until the end of the rows, or the first row past the cap. */
    suspend fun read() {
        var going = true
        while (going) {
            going = when (xml.next()) {
                XmlPullParser.END_DOCUMENT -> false
                XmlPullParser.START_TAG -> start()
                XmlPullParser.END_TAG -> end()
                else -> true
            }
        }
    }

    /** False once the sheet is read as far as it is shown. */
    private suspend fun start(): Boolean {
        when (xml.name) {
            "row" -> {
                row = xml.attribute(null, "r")?.let(::rowOf) ?: (row + 1)
                column = -1
                return grid.keeps(row, 0)
            }
            "c" -> {
                column = xml.attribute(null, "r")?.let(::columnOf) ?: (column + 1)
                type = xml.attribute(null, "t")
                style = xml.attribute(null, "s")?.toIntOrNull() ?: 0
                value.clear()
                inline.clear()
            }
            "v" -> value.collect(xml)
            "is" -> inlineString()
            "f", "extLst" -> xml.skip()
        }
        return true
    }

    /** False at the end of the rows: what follows them (merged cells, drawings) is not shown. */
    private fun end(): Boolean {
        if (xml.name == "c") cell()
        return xml.name != "sheetData"
    }

    /** `<is>`: its texts, runs joined, phonetic reading left out. */
    private suspend fun inlineString() {
        val depth = xml.depth
        var event = xml.next()
        while (event != XmlPullParser.END_TAG || xml.depth != depth) {
            when {
                event == XmlPullParser.END_DOCUMENT -> throw UnreadableFileException("an element never closed")
                event != XmlPullParser.START_TAG -> Unit
                xml.name == "t" -> inline.collect(xml)
                xml.name == "rPh" -> xml.skip()
            }
            event = xml.next()
        }
    }

    private fun cell() {
        val raw = value.shown()
        when (type) {
            "s" -> if (raw.isNotEmpty()) grid.shared(row, column, raw.trim().toIntOrNull()?.takeIf { it >= 0 } ?: incoherent())
            "inlineStr" -> grid.text(row, column, inline.shown())
            "str", "e", "d" -> grid.text(row, column, raw)
            "b" -> if (raw.isNotEmpty()) grid.text(row, column, cellText.booleanText(raw.trim() == "1"))
            else -> if (raw.isNotBlank()) {
                grid.number(
                    row,
                    column,
                    raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: incoherent(),
                    style
                )
            }
        }
    }

    private fun incoherent(): Nothing = throw UnreadableFileException("a value that is not one of its type")

    private companion object {
        const val LETTERS = 26

        /** "12" -> 11, the row's index from 0; past any sheet's last row, it is past the cap all the same. */
        fun rowOf(reference: String): Int {
            val n = reference.toLongOrNull()?.takeIf { it >= 1 } ?: throw UnreadableFileException("a row numbered $reference")
            return minOf(n - 1, Int.MAX_VALUE.toLong()).toInt()
        }

        /** "AB12" -> 27, the column's index from 0; letters past any sheet's last column stop counting. */
        fun columnOf(reference: String): Int {
            val letters = reference.takeWhile { it in 'A'..'Z' }
            val column = letters.fold(0L) { n, c -> minOf(n * LETTERS + (c - 'A' + 1), Int.MAX_VALUE.toLong()) }
            if (column == 0L) throw UnreadableFileException("a cell referenced as $reference")
            return (column - 1).toInt()
        }
    }
}

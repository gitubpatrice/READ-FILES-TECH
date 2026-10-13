package com.readfilestech.read_files_tech.check

import android.os.Debug
import android.os.SystemClock
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.images.ImageTooLargeException
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import com.readfilestech.read_files_tech.data.viewer.document.DocumentText
import com.readfilestech.read_files_tech.data.viewer.html.HtmlPage
import com.readfilestech.read_files_tech.data.viewer.json.JsonContent
import com.readfilestech.read_files_tech.data.viewer.json.JsonDocument
import com.readfilestech.read_files_tech.data.viewer.json.JsonFile
import com.readfilestech.read_files_tech.data.viewer.json.JsonTree
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownFile
import com.readfilestech.read_files_tech.data.viewer.sheet.Workbook
import com.readfilestech.read_files_tech.data.viewer.text.TextColors
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.data.viewer.zip.ZipArchive
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind
import com.readfilestech.read_files_tech.ui.viewer.ViewerResources
import com.readfilestech.read_files_tech.ui.viewer.image.ImagePages
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import org.json.JSONArray
import java.io.File
import java.io.IOException

/** One file of the corpus and what its viewer must make of it. */
data class CorpusCase(val file: String, val viewer: ViewerKind, val expect: String, val maxMillis: Long)

/**
 * The hostile corpus of phase 4 (port plan, phase 4, finding 8): each file of a folder that
 * `tools/corpus/corpus.py` pushed is opened by its viewer's load, as the screen does — in the `check`
 * build, the release as R8 makes it. Its `corpus.json` names, per file, the viewer and the outcome
 * expected (`ok`, `too_large`, `too_many_pixels`, `too_many_items`, `unreadable`, `unsupported`,
 * `out_of_memory`, `cannot_open`); the report gives the outcome, the time, the heap kept by the result,
 * and whatever was written in the app's sandbox or in the corpus folder meanwhile — a viewer writes nothing.
 *
 * A viewer of phase 4 adds its branch to [load]: the `when` is exhaustive, the build fails without it.
 */
class CorpusCheck(private val sandbox: File) {
    suspend fun run(dir: File): Map<String, Any?> {
        val cases = parse(File(dir, MANIFEST).readText())
        return linkedMapOf("results" to cases.map { case -> check(dir, case) })
    }

    /** Every failure is the corpus's datum, whatever its class: reported through the screens' own guard. */
    private suspend fun check(dir: File, case: CorpusCase): Map<String, Any?> {
        val before = Sandbox.list(sandbox) to Sandbox.list(dir)
        val resources = ViewerResources()
        val heapBefore = usedHeap()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()
        val start = SystemClock.elapsedRealtime()
        val result = loadOrFailure { load(case.viewer, StorageFileSource(File(dir, case.file)), resources) }
        val millis = SystemClock.elapsedRealtime() - start
        val heapKept = usedHeap() - heapBefore
        val nativeKept = Debug.getNativeHeapAllocatedSize() - nativeBefore
        val failure = result.exceptionOrNull()
        val outcome = failure?.let(::outcomeOf) ?: OK
        // Read once the heap is measured: the result stays reachable until then.
        check(failure != null || result.getOrNull() != null)
        resources.close()
        val after = Sandbox.list(sandbox) to Sandbox.list(dir)
        return linkedMapOf(
            "file" to case.file,
            "viewer" to case.viewer.name,
            "expect" to case.expect,
            "outcome" to outcome,
            "detail" to failure?.javaClass?.name,
            "millis" to millis,
            "maxMillis" to case.maxMillis,
            "heapKeptBytes" to heapKept,
            "nativeKeptBytes" to nativeKept,
            "wroteInSandbox" to (after.first - before.first.toSet()).map { it["path"] },
            "changedInCorpus" to (after.second - before.second.toSet()).map { it["path"] },
        )
    }

    /**
     * What each viewer's screen loads — the same calls, so the corpus measures what the user meets. A
     * load that opens what must be closed (a ZipFile, a PdfRenderer) keeps it in [resources], as its
     * screen does.
     */
    // CyclomaticComplexMethod: one branch per viewer, the `when` exhaustive on purpose (a viewer added fails the build).
    @Suppress("CyclomaticComplexMethod")
    private suspend fun load(kind: ViewerKind, source: ViewerSource, resources: ViewerResources): Any = when (kind) {
        // Strictly here, where the screen tolerates it: a failure of the colours is the corpus's to see.
        ViewerKind.TEXT -> TextDocument.read(source).also { document ->
            TextColors.extract(document)
            decodeScreenRows(document)
        }
        ViewerKind.CSV -> CsvTable.read(source).also { table ->
            // The cells the screen decodes first, on its main thread: the header, the first rows, the last one.
            val columns = minOf(table.columns, FIRST_SCREEN_COLUMNS)
            table.header(0, columns, CsvTable.SHOWN_CHARS)
            for (row in 0 until minOf(table.rows, FIRST_SCREEN_ROWS)) table.cells(row, 0, columns, CsvTable.SHOWN_CHARS)
            if (table.rows > 0) table.cells(table.rows - 1, 0, columns, CsvTable.SHOWN_CHARS)
        }
        // A page of a phone held upright: the bounds, then the decode at the size the page shows it at.
        ViewerKind.IMAGE -> ImagePages.decode(source, PAGE_WIDTH, PAGE_HEIGHT, resources)
        ViewerKind.ZIP -> resources.keep(ZipArchive.open(source))
        // A document's text is shown by the text viewer's rows.
        ViewerKind.DOCUMENT -> DocumentText.read(source).also { decodeScreenRows(it.document) }
        ViewerKind.SHEET -> resources.keep(Workbook.open(source)).also { formatScreenCells(it) }
        // The rendering (its blocks are laid out as they show), and the first rows of the source.
        ViewerKind.MARKDOWN -> MarkdownFile.read(source).also { decodeScreenRows(it.source) }
        ViewerKind.JSON -> loadJson(source)
        ViewerKind.HTML -> loadHtml(source)
    }

    /** The tree's rows with the document's value opened, and the texts of the first ones; or the text. */
    private suspend fun loadJson(source: ViewerSource): Any = when (val content = JsonFile.read(source)) {
        is JsonContent.Tree -> JsonTree(content.document).also { decodeTreeRows(content.document, it) }
        is JsonContent.Text -> content.also { decodeScreenRows(it.text) }
    }

    /** The texts of the tree rows the screen shows first, on its main thread, and of the last one. */
    private fun decodeTreeRows(document: JsonDocument, tree: JsonTree) {
        val rows = (0 until minOf(tree.rowCount, FIRST_SCREEN_ROWS)) + (tree.rowCount - 1)
        for (row in rows) {
            val value = tree.value(row)
            document.keyLiteral(value, TextDocument.ROW_BYTES)
            if (document.isContainer(value)) tree.childCount(value) else document.literal(value, TextDocument.ROW_BYTES)
        }
    }

    /** The page and its colours as the screen reads them; then what its loader would serve (no WebView here). */
    private suspend fun loadHtml(source: ViewerSource): HtmlPage {
        val page = HtmlPage.read(source)
        TextColors.extract(page.source)
        decodeScreenRows(page.source)
        HtmlProbe(sandbox, requireNotNull(source.shareable?.parentFile)).probe(page)
        return page
    }

    /**
     * The workbook's first sheet, as the spreadsheet viewer opens it, and what it formats first on its main
     * thread: the header, the first rows, the last one.
     */
    private suspend fun formatScreenCells(workbook: Workbook) {
        if (workbook.sheetNames.isEmpty()) return
        val sheet = workbook.sheet(0)
        val columns = minOf(sheet.columns, FIRST_SCREEN_COLUMNS)
        sheet.header(0, columns)
        for (row in 0 until minOf(sheet.rows, FIRST_SCREEN_ROWS)) sheet.cells(row, 0, columns)
        if (sheet.rows > 0) sheet.cells(sheet.rows - 1, 0, columns)
    }

    /** The rows the screen decodes first, on its main thread, and the last one. */
    private fun decodeScreenRows(document: TextDocument) {
        for (index in 0 until minOf(document.rowCount, FIRST_SCREEN_ROWS)) document.row(index)
        document.row(document.rowCount - 1)
    }

    private fun outcomeOf(error: Throwable): String = when (error) {
        is OutOfMemoryError -> OUT_OF_MEMORY
        is FileTooLargeException -> "too_large"
        is ImageTooLargeException -> "too_many_pixels"
        is TooManyItemsException -> "too_many_items"
        is UnsupportedFormatException -> "unsupported"
        is UnreadableFileException -> UNREADABLE
        is IOException -> "cannot_open"
        // What the screen says "unreadable" for: a parser's own exception on a hostile file, a stack overflow.
        else -> UNREADABLE
    }

    private fun usedHeap(): Long {
        val runtime = Runtime.getRuntime()
        runtime.gc()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun parse(json: String): List<CorpusCase> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            CorpusCase(
                file = item.getString("file"),
                viewer = requireNotNull(ViewerKind.fromArgument(item.getString("viewer"))) { "unknown viewer in $item" },
                expect = item.getString("expect"),
                maxMillis = item.optLong("maxMillis", DEFAULT_MAX_MILLIS),
            )
        }
    }

    private companion object {
        const val MANIFEST = "corpus.json"
        const val OK = "ok"
        const val UNREADABLE = "unreadable"
        const val OUT_OF_MEMORY = "out_of_memory"
        const val DEFAULT_MAX_MILLIS = 15_000L

        /** About what a phone shows of a text at once, and then some. */
        const val FIRST_SCREEN_ROWS = 100

        /** About what a phone shows of a table across (columns of 136 dp), and then some. */
        const val FIRST_SCREEN_COLUMNS = 8

        /** The page the image viewer decodes for: a phone of 1080 x 2400 held upright. */
        const val PAGE_WIDTH = 1080
        const val PAGE_HEIGHT = 2400
    }
}

package com.readfilestech.read_files_tech.ui.viewer.pdf

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.pdf.OutlineEntry
import com.readfilestech.read_files_tech.data.viewer.pdf.PageSize
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfDocument
import com.readfilestech.read_files_tech.data.viewer.pdf.RecyclingCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The pages of an open PDF as the screen draws them: rendered by the `:pdf` process one at a time, only
 * when a page comes into view (a document of 100 000 pages opens on its first), each one's size learnt
 * then — until it is, a page is laid out with the first page's. The renders the screen scrolled past are
 * kept in a [RecyclingCache] of [FileCaps.PDF_PAGE_BITMAPS], the oldest recycled.
 */
class PdfPageImages(val document: PdfDocument, io: CoroutineDispatcher) : AutoCloseable {
    // One call at a time: the `:pdf` process renders one page at a time anyway, and a page that left the
    // screen before its turn is never rendered.
    private val renders = io.limitedParallelism(1)
    private val cache = RecyclingCache<Pair<Int, Int>, Bitmap>(
        FileCaps.PDF_PAGE_BITMAPS,
        { it.allocationByteCount.toLong() },
        Bitmap::recycle
    )

    /** The sizes learnt so far; the screen reads them as it lays the pages out. */
    val sizes: SnapshotStateMap<Int, PageSize> = mutableStateMapOf(0 to document.firstPage)

    private val entries = MutableStateFlow<List<OutlineEntry>>(emptyList())

    /** The outline, once read: empty until then, and when the file has none — no "Contents" then. */
    val outline: StateFlow<List<OutlineEntry>> = entries.asStateFlow()

    fun sizeOf(index: Int): PageSize = sizes[index] ?: document.firstPage

    /** Page [index] in a column [width] pixels wide, in at most [maxPixels]: the caller owns it, then [give]s it back. */
    suspend fun take(index: Int, width: Int, maxPixels: Long): Bitmap {
        cache.take(index to width)?.let { return it }
        val rendered = withContext(renders) {
            ensureActive()
            document.render(index, width, null, maxPixels)
        }
        sizes[index] = rendered.size
        return rendered.bitmap
    }

    /** A render [take]n, back to the cache: the page left the screen. */
    fun give(index: Int, width: Int, bitmap: Bitmap) = cache.give(index to width, bitmap)

    /** A sharper render of the part of a page on screen (PdfView.tiles); the caller recycles it. */
    suspend fun tile(request: TileRequest, maxPixels: Long): Bitmap = withContext(renders) {
        ensureActive()
        document.render(request.index, request.width, request.region, maxPixels).bitmap
    }

    /** Reads the outline in the `:pdf` process; blocking. */
    fun readOutline() {
        entries.value = document.outline()
    }

    override fun close() = cache.clear()
}

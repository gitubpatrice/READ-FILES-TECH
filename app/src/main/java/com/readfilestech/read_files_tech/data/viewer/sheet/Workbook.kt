package com.readfilestech.read_files_tech.data.viewer.sheet

import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.document.DocumentXml
import com.readfilestech.read_files_tech.data.viewer.document.XmlEvents
import com.readfilestech.read_files_tech.data.viewer.zip.ZipArchive
import com.readfilestech.read_files_tech.data.viewer.zip.ZipEntryException
import com.readfilestech.read_files_tech.data.viewer.zip.ZipItem
import com.readfilestech.read_files_tech.data.viewer.zip.ZipProblem
import org.apache.poi.EmptyFileException
import org.apache.poi.poifs.filesystem.FileMagic
import org.xmlpull.v1.XmlPullParserException
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.util.Locale

/**
 * A workbook opened for the viewer (`XlsxViewerScreen` of the 2.15.3): the names of its sheets, in their
 * order, and each sheet read when it is shown — one at a time, never all of them (the 2.15.3 decoded every
 * sheet whole, inventory D21). Open until [close]: an .xls is read from its OLE file (held whole), an .xlsx or
 * .ods from its archive, on demand.
 *
 * The format is told by the content, not by the name (the 2.15.3 sent `.ods` to its own reader by the name,
 * so an .xlsx named .ods was "not a valid .ods"): an OLE file is an .xls (or an encrypted .xlsx, a format not
 * opened), a ZIP archive an .ods when it holds a `content.xml`, an .xlsx when it holds a package's
 * relationships.
 */
abstract class Workbook internal constructor() : Closeable {
    abstract val sheetNames: List<String>

    private val lock = Any()
    private var reading = 0
    private var closed = false
    private var released = false

    /**
     * Sheet [index] of [sheetNames], read now. A [close] meanwhile (the screen left while another sheet was
     * read) takes effect when the read ends: the archive is never closed under a reader.
     *
     * @throws IOException the workbook is closed.
     */
    suspend fun sheet(index: Int): Sheet {
        synchronized(lock) {
            if (closed) throw IOException("workbook closed")
            reading++
        }
        try {
            return read(index)
        } finally {
            val now = synchronized(lock) {
                reading--
                releasable()
            }
            if (now) release()
        }
    }

    final override fun close() {
        val now = synchronized(lock) {
            closed = true
            releasable()
        }
        if (now) release()
    }

    /** Whether the workbook is to be released now: closed, no read running, not released yet. */
    private fun releasable(): Boolean {
        if (!closed || reading > 0 || released) return false
        released = true
        return true
    }

    protected abstract suspend fun read(index: Int): Sheet

    /** Closes what the workbook holds, once, when it is closed and no read runs. */
    protected abstract fun release()

    companion object {
        /** Opens [source] within the 2.15.3's caps, its numbers in the user's [locale]. */
        suspend fun open(source: ViewerSource, locale: Locale = Locale.getDefault()): Workbook = open(source, SheetLimits(), locale)

        internal suspend fun open(source: ViewerSource, limits: SheetLimits, locale: Locale): Workbook = when (magic(source, limits)) {
            FileMagic.OLE2 -> XlsWorkbook.open(source, limits, locale)
            FileMagic.OOXML -> zipped(source, limits, locale)
            FileMagic.BIFF2, FileMagic.BIFF3, FileMagic.BIFF4 -> throw UnsupportedFormatException("Excel 2, 3 or 4")
            else -> throw UnreadableFileException("neither an OLE file nor a ZIP archive")
        }

        private fun magic(source: ViewerSource, limits: SheetLimits): FileMagic = try {
            source.open(limits.fileBytes).use { FileMagic.valueOf(FileMagic.prepareToCheckMagic(it)) }
        } catch (e: EmptyFileException) {
            throw UnreadableFileException("an empty file: no workbook is", e)
        }

        private suspend fun zipped(source: ViewerSource, limits: SheetLimits, locale: Locale): Workbook {
            val archive = ZipArchive.open(source, limits.fileBytes)
            var workbook: Workbook? = null
            try {
                val parts = SheetParts(archive)
                workbook = when {
                    parts[OdsWorkbook.CONTENT] != null -> OdsWorkbook.open(parts, limits)
                    parts[XlsxWorkbook.PACKAGE_RELATIONSHIPS] != null -> XlsxWorkbook.open(parts, limits, locale)
                    else -> throw UnreadableFileException("a ZIP archive that holds no workbook")
                }
                return workbook
            } finally {
                if (workbook == null) archive.close()
            }
        }
    }
}

/**
 * The parts of a workbook's archive, by name — told apart without case, as OPC names them: two entries of
 * one name make the archive unreadable rather than one of them read by chance.
 */
internal class SheetParts(val archive: ZipArchive) {
    private val byName: Map<String, ZipItem> = HashMap<String, ZipItem>().also { map ->
        archive.entries.filterNot(ZipItem::isDirectory).forEach { item ->
            if (map.put(key(item.name), item) != null) throw UnreadableFileException("two parts named ${item.name}")
        }
    }

    operator fun get(name: String): ZipItem? = byName[key(name)]

    /**
     * Reads [item] as XML within [limits], at most `partBytes` of it whatever it declares: a [whole] part
     * (relationships, workbook, styles, strings) declaring more is refused before a byte is inflated; a
     * sheet is read up to its last row shown.
     */
    suspend fun <T> parse(item: ZipItem, limits: SheetLimits, whole: Boolean, read: suspend (XmlEvents) -> T): T {
        if (whole && item.size > limits.partBytes) throw FileTooLargeException(limits.partBytes)
        return damagedAsUnreadable {
            archive.readStart(item, limits.partBytes).use { input ->
                read(XmlEvents(DocumentXml.parser(input, limits.stretchChars), limits.depth, limits.elements))
            }
        }
    }

    private fun key(name: String) = name.trimStart('/').lowercase(Locale.ROOT)
}

/**
 * What a damaged workbook throws, as a refusal in words: an entry that does not hold or is encrypted, XML
 * that is not well formed, a character reference to no character.
 */
internal inline fun <T> damagedAsUnreadable(read: () -> T): T = try {
    read()
} catch (e: ZipEntryException) {
    throw if (e.problem == ZipProblem.ENCRYPTED || e.problem == ZipProblem.UNSUPPORTED_METHOD) {
        UnsupportedFormatException("an entry ${e.problem}").apply { initCause(e) }
    } else {
        UnreadableFileException("a damaged entry", e)
    }
} catch (e: EOFException) {
    throw UnreadableFileException("an entry cut short", e)
} catch (e: XmlPullParserException) {
    throw UnreadableFileException("XML that is not well formed", e)
} catch (e: IllegalArgumentException) {
    throw UnreadableFileException("XML the parser rejected", e)
}

package com.readfilestech.read_files_tech.data.viewer.document

import com.readfilestech.read_files_tech.data.files.BoundedInputStream
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import org.xmlpull.v1.XmlPullParserException
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** What bounds the reading of a document; the 2.15.3's caps, smaller ones in tests only. */
internal data class DocumentLimits(
    /** The file itself (the 2.15.3's `docZipped`). */
    val fileBytes: Long = FileCaps.DOC_ZIPPED,
    /** One XML part once decompressed — what the file's size does not bound. */
    val partBytes: Long = FileCaps.ZIP_ENTRY_DECOMPRESSED,
    /** The text shown, in UTF-8: what the text viewer's rows show. */
    val textBytes: Long = FileCaps.TEXT_VIEWER,
    val stretchChars: Long = FileCaps.XML_STRETCH_CHARS,
    val depth: Int = FileCaps.XML_DEPTH,
)

/**
 * A document opened for the viewer (`DocxViewerScreen` of the 2.15.3): its text, shown by the text
 * viewer's rows. [hasText] false: there is nothing but white space to show (a document of pictures).
 *
 * The format is told by the content, not by the name: an OLE file (`.doc` of Word 97-2003, or a Word
 * document encrypted with a password) is a format the viewer does not open; a ZIP archive is a Word
 * document if it holds one's main part, an OpenDocument if it holds a `content.xml`; anything else is
 * not a document. The 2.15.3 chose by the extension: a `.docx` saved as `.odt` was refused for want of
 * a `content.xml`, and an OLE file named `.odt` was called an unreadable ZIP.
 *
 * Everything opened here is closed before [read] returns, by `use` — cancellation included — so a vault's
 * private copy is deleted as soon as the text is out of it (D-028).
 */
class DocumentText internal constructor(val document: TextDocument, val hasText: Boolean) {
    companion object {
        /** Reads [source] within the 2.15.3's caps. */
        suspend fun read(source: ViewerSource): DocumentText = read(source, DocumentLimits())

        internal suspend fun read(source: ViewerSource, limits: DocumentLimits): DocumentText =
            source.acquireFile(limits.fileBytes).use { acquired ->
                if (isOle(acquired.file)) throw UnsupportedFormatException("an OLE compound file: Word 97-2003, or encrypted")
                damagedAsUnreadable { ZipFile(acquired.file).use { zip -> text(zip, limits) } }
            }

        private suspend fun text(zip: ZipFile, limits: DocumentLimits): DocumentText {
            WordText.mainPart(zip, limits)?.let { return WordText.read(zip, it, limits) }
            val content = zip.getEntry(OdfText.CONTENT) ?: throw UnreadableFileException("a ZIP archive that holds no document")
            return OdfText.read(zip, content, limits)
        }

        /**
         * What a damaged file throws, as a refusal in words: a ZIP that is not one or ends early, XML
         * that is not well formed, a character reference to no character (`&#x110000;`).
         */
        private inline fun <T> damagedAsUnreadable(read: () -> T): T = try {
            read()
        } catch (e: ZipException) {
            throw UnreadableFileException("a damaged ZIP archive", e)
        } catch (e: EOFException) {
            throw UnreadableFileException("a ZIP entry cut short", e)
        } catch (e: XmlPullParserException) {
            throw UnreadableFileException("XML that is not well formed", e)
        } catch (e: IllegalArgumentException) {
            throw UnreadableFileException("XML the parser rejected", e)
        }

        /** The 8 bytes every OLE compound file starts with ([MS-CFB] 2.2); the 2.15.3 checked the first 4. */
        private val OLE_SIGNATURE = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
        private const val BYTE = 0xFF

        private fun isOle(file: File): Boolean {
            val head = ByteArray(OLE_SIGNATURE.size)
            var size = 0
            FileInputStream(file).use { input ->
                while (size < head.size) {
                    val n = input.read(head, size, head.size - size)
                    if (n == -1) break
                    size += n
                }
            }
            return size == head.size && OLE_SIGNATURE.indices.all { (head[it].toInt() and BYTE) == OLE_SIGNATURE[it] }
        }
    }
}

/** Reads [part] of the archive as XML, bounded: its decompressed bytes, its stretches, its depth. */
internal suspend fun <T> ZipFile.parsePart(part: ZipEntry, limits: DocumentLimits, read: suspend (XmlEvents) -> T): T =
    BoundedInputStream(getInputStream(part), limits.partBytes).use { input ->
        read(XmlEvents(DocumentXml.parser(input, limits.stretchChars), limits.depth))
    }

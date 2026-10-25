package com.readfilestech.read_files_tech.data.viewer.markdown

import com.readfilestech.read_files_tech.data.files.FileCaps
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The local images of a Markdown file (`_imageBuilder` of the 2.15.3, `md_viewer_screen.dart:63-93`): an
 * image shows when its destination is a relative path to a file in the document's own [folder] or below it.
 * Never a URL of any scheme (http, https, data, file, content…), an absolute path, a `..`, nor a link that
 * leads out of the folder once resolved: the real path must lie under the folder's real path. A document
 * with no folder — the vault's, whose plaintext has no file beside it (D-028) — shows none.
 *
 * Only the first [limit] local destinations are looked up on the storage; the others keep their icon.
 *
 * @param realPath the real path of a file, links resolved ([canonical]); the tests give another, the
 *   system refusing them a symbolic link on Windows.
 */
class MarkdownImages(
    folder: File?,
    private val limit: Int = FileCaps.MARKDOWN_IMAGES,
    private val realPath: (File) -> File? = ::canonical,
) {
    private val root: File? = folder?.let(realPath)
    private var looked = 0

    /** The real file [destination] names, or null: refused, missing, not a file, or past the limit. */
    fun resolve(destination: String): File? {
        val root = root
        val path = relativePath(destination)
        if (root == null || path == null || looked >= limit) return null
        looked++
        return realPath(File(root, path))?.takeIf { isInside(root, it) && it.isFile }
    }

    companion object {
        /**
         * [destination] as a path relative to the folder — percent-escapes decoded, query and fragment
         * dropped — or null when it is anything else: a scheme or a drive (any `:`), an absolute path, a
         * `..` segment, a NUL, a malformed escape (the 2.15.3's `Uri.decodeFull` threw on it, `:78`).
         */
        internal fun relativePath(destination: String): String? {
            val path = percentDecoded(destination.substringBefore('#').substringBefore('?'))?.replace('\\', '/')
                ?.takeUnless { ':' in it || it.startsWith('/') || NUL in it } ?: return null
            val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
            return segments.takeIf { it.isNotEmpty() && ".." !in it }?.joinToString(File.separator)
        }

        /** Whether [file] (a real path) lies under [root] (a real path). */
        internal fun isInside(root: File, file: File): Boolean = file.path.startsWith(root.path + File.separator)

        /** The real path of [file], links resolved; null when the storage cannot tell. */
        fun canonical(file: File): File? = try {
            file.canonicalFile
        } catch (expected: IOException) {
            null
        }

        /**
         * [text] with its `%XX` escapes decoded as UTF-8; null for a malformed escape or invalid UTF-8. Also
         * how the reader decodes an EPUB's references to its own files.
         */
        internal fun percentDecoded(text: String): String? {
            val bytes = ByteArrayOutputStream(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%') {
                    val hex = text.substring(i + 1, minOf(text.length, i + ESCAPE_LENGTH))
                    if (hex.length != ESCAPE_LENGTH - 1 || !hex.all { it in HEX_DIGITS }) return null
                    bytes.write(hex.toInt(HEX))
                    i += ESCAPE_LENGTH
                } else {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (expected: CharacterCodingException) {
                null
            }
        }

        private const val HEX = 16
        private const val HEX_DIGITS = "0123456789abcdefABCDEF"
        private const val ESCAPE_LENGTH = 3
        private const val NUL = '\u0000'
    }
}

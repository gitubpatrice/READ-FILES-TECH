package com.readfilestech.read_files_tech.data.viewer.html

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The folder of a page, from which the loader serves what the page references — its pictures, styles and
 * fonts, an Edge "Save as, complete" page's `_files` folder — and nothing above it (plan, phase 4; the
 * 2.15.3 let the WebView read any `file:`, inventory D20).
 *
 * [root] is a real path: a path is resolved, links followed (`toRealPath`, which also follows them where
 * `canonicalFile` does not — measured on Windows), and must still be inside it.
 */
class HtmlFolder private constructor(private val root: Path) {
    /**
     * The file at [path] — decoded, relative to the folder, `/` between its names, as the loader hands it
     * (WebViewAssetLoader decodes `%2F` and `%2E` too) — or null when it is not a file of the folder: a `..`
     * or `.` name, an empty one, a backslash, a NUL, a link that leads out, a folder, nothing.
     */
    fun resolve(path: String): File? {
        if (!path.split('/').all(::isPlainName)) return null
        val file = try {
            root.resolve(path).toRealPath()
        } catch (expected: IOException) {
            // Nothing there.
            null
        } catch (expected: InvalidPathException) {
            null
        }
        return file?.takeIf { it.startsWith(root) && Files.isRegularFile(it) }?.toFile()
    }

    /** A name of a file or folder as it is: never cleaned up, so neither `..` nor `.`, nor a backslash or a NUL. */
    private fun isPlainName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && BACKSLASH !in name && NUL !in name

    companion object {
        private const val BACKSLASH = '\\'
        private const val NUL = '\u0000'

        /** The folder [dir], resolved now; null when it cannot be. Reads the disk: off the main thread. */
        fun of(dir: File): HtmlFolder? = try {
            HtmlFolder(dir.toPath().toRealPath())
        } catch (expected: IOException) {
            null
        } catch (expected: InvalidPathException) {
            null
        }
    }
}

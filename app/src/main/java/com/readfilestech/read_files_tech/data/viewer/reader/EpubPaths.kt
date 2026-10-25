package com.readfilestech.read_files_tech.data.viewer.reader

import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownImages

/** The references of a book to its own files: an `href` resolved against the folder of the file it is in. */
internal object EpubPaths {
    /**
     * The entry of the archive [href] names from a file in [folder] (`""` at the archive's root):
     * percent-escapes decoded, its fragment and query dropped, its `.` and `..` segments applied. Null when it
     * names no entry: empty, a scheme or a drive (a `:` before its first `/`), an absolute path, a control
     * character, a malformed escape, or a `..` that would leave the archive's root — however it is spelled,
     * `%2E%2E` or `..\`. A `..` that stays inside is followed: the 2.15.3 refused any chapter outside the
     * OPF's own folder (`reader_service.dart:135-142`), so a book whose OPF is in a folder beside its
     * chapters' (`OEBPS/package/book.opf` naming `../text/c1.xhtml`) showed none of them.
     */
    fun resolve(folder: String, href: String): String? {
        val path = MarkdownImages.percentDecoded(href.substringBefore('#').substringBefore('?'))
            ?.replace('\\', '/')
            ?.takeIf { it.isNotEmpty() && !isRooted(it) && it.none(Char::isISOControl) }
        val segments = ArrayDeque(folder.split('/').filter(String::isNotEmpty))
        val inside = path != null && path.split('/').all { segment -> step(segments, segment) }
        return if (inside) segments.joinToString("/").ifEmpty { null } else null
    }

    /** The folder of the entry [name], `""` at the root: what its own references are resolved against. */
    fun folderOf(name: String): String = name.substringBeforeLast('/', "")

    /** An absolute path, or one with a scheme or a drive: nothing relative to a file of the book. */
    private fun isRooted(path: String): Boolean = path.startsWith('/') || ':' in path.substringBefore('/')

    /** [segment] applied to [segments]; false when it is a `..` above the root. */
    private fun step(segments: ArrayDeque<String>, segment: String): Boolean = when (segment) {
        "", "." -> true
        ".." -> segments.removeLastOrNull() != null
        else -> segments.add(segment)
    }
}

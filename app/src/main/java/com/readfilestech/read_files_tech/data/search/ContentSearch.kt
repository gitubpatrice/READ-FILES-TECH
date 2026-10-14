package com.readfilestech.read_files_tech.data.search

import com.readfilestech.read_files_tech.data.browse.FileNames
import com.readfilestech.read_files_tech.data.files.FileCaps
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.CoroutineContext

/**
 * One matching line: its number (from 1), [text] — the line, or a window of it around its first match —
 * and the matches inside [text], to highlight. [cutBefore] / [cutAfter]: the line goes on that side.
 */
data class LineMatch(val line: Int, val text: String, val matches: List<IntRange>, val cutBefore: Boolean, val cutAfter: Boolean)

/** A file holding the pattern: the first [LineMatch]es, and how many lines match in all. */
data class ContentHit(val path: String, val lines: List<LineMatch>, val lineCount: Int)

sealed interface ContentEvent {
    data class Found(val hits: List<ContentHit>) : ContentEvent

    data class Scanned(val files: Int) : ContentEvent

    /**
     * The last event: what was not searched, said rather than read as "not there" — [tooLarge] text files
     * over [FileCaps.SEARCH_CONTENT], [unreadable] files and [unreadableFolders] that could not be read —
     * and whether the search stopped at [FileCaps.CONTENT_SEARCH_FILES] files found ([full]).
     */
    data class Finished(val scanned: Int, val tooLarge: Int, val unreadable: Int, val unreadableFolders: Int, val full: Boolean) :
        ContentEvent
}

/**
 * The content search (`content_search_screen.dart` of the 2.15.3): the text files of a folder and its
 * sub-folders holding a word or a regular expression, line by line. On the global search's walk and
 * reading ([FolderWalk], [TextContent]): a folder that cannot be read is skipped and counted (the 2.15.3
 * stopped there, D9), a Latin-1 text is decoded (D35), symbolic links are not followed (the 2.15.3's
 * `list(recursive: true)` followed them). The trash and `Android/data` are not searched; hidden folders are,
 * as in the 2.15.3. Cancelled by cancelling its collector, a long line included.
 */
object ContentSearch {
    /** The 2.15.3's `_textExts` (`content_search_screen.dart:30-50`). */
    val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "xml", "json", "html", "htm", "css", "js", "php", "dart", "ts", "yaml", "yml", "ini", "conf", "log",
        "sh", "bat",
    )

    fun run(root: File, pattern: SearchPattern, maxFiles: Int = FileCaps.CONTENT_SEARCH_FILES): Flow<ContentEvent> = flow {
        val search = Search(pattern, currentCoroutineContext())
        val batches = SearchBatches<ContentHit>({ emit(ContentEvent.Found(it)) }, { emit(ContentEvent.Scanned(it)) })
        val walk = FolderWalk(root, skipHidden = false) { FolderWalk.isTrash(it) || FolderWalk.isAppData(it) }
        walk.forEachFile { path, attributes ->
            search.look(path, attributes)?.let(batches::add)
            batches.report(search.scanned)
            search.found < maxFiles
        }
        batches.finish(search.scanned)
        emit(ContentEvent.Finished(search.scanned, search.tooLarge, search.unreadable, walk.unreadableFolders, search.found >= maxFiles))
    }

    private class Search(private val pattern: SearchPattern, private val job: CoroutineContext) {
        var scanned = 0
            private set
        var found = 0
            private set
        var tooLarge = 0
            private set
        var unreadable = 0
            private set
        private val check = { job.ensureActive() }

        fun look(path: Path, attributes: BasicFileAttributes): ContentHit? {
            val name = path.fileName?.toString().orEmpty()
            if (FileNames.extension(name) !in TEXT_EXTENSIONS) return null
            scanned++
            val text = if (attributes.size() > FileCaps.SEARCH_CONTENT) {
                tooLarge++
                null
            } else {
                textOf(path)
            }
            return text?.let { hitIn(path, it) }?.also { found++ }
        }

        private fun textOf(path: Path): String? = try {
            TextContent.read(path, FileCaps.SEARCH_CONTENT)
        } catch (expected: IOException) {
            unreadable++
            null
        }

        private fun hitIn(path: Path, text: String): ContentHit? {
            val kept = mutableListOf<LineMatch>()
            var count = 0
            text.lineSequence().forEachIndexed { index, line ->
                job.ensureActive()
                val first = pattern.find(line, check = check) ?: return@forEachIndexed
                count++
                if (kept.size < FileCaps.CONTENT_SEARCH_LINES) kept += window(index + 1, line, first)
            }
            return if (count == 0) null else ContentHit(path.toString(), kept, count)
        }

        /**
         * The line, or [FileCaps.CONTENT_SEARCH_LINE_CHARS] of it starting a little before its first match —
         * the 2.15.3 kept every line whole, a minified file's 2 MiB line included — with the matches inside.
         */
        private fun window(number: Int, line: String, first: IntRange): LineMatch {
            val size = FileCaps.CONTENT_SEARCH_LINE_CHARS
            var start = if (line.length <= size) 0 else (first.first - LEAD).coerceIn(0, line.length - size)
            // Never cutting a surrogate pair in two.
            if (start > 0 && line[start].isLowSurrogate()) start--
            var end = minOf(line.length, start + size)
            if (end < line.length && line[end - 1].isHighSurrogate()) end--
            val text = line.substring(start, end)
            return LineMatch(number, text, matchesIn(text), cutBefore = start > 0, cutAfter = end < line.length)
        }

        /** The matches inside a window, at most [MAX_HIGHLIGHTS]; an empty match highlights nothing. */
        private fun matchesIn(text: String): List<IntRange> {
            val matches = mutableListOf<IntRange>()
            var from = 0
            while (from <= text.length && matches.size < MAX_HIGHLIGHTS) {
                val match = pattern.find(text, from, check) ?: break
                if (!match.isEmpty()) matches += match
                from = if (match.isEmpty()) match.first + 1 else match.last + 1
            }
            return matches
        }
    }

    /** Characters shown before a match in a cut line. */
    private const val LEAD = 40

    private const val MAX_HIGHLIGHTS = 20
}

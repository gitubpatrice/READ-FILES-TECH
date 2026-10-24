package com.readfilestech.read_files_tech.data.search

import com.readfilestech.read_files_tech.data.browse.FileNames
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.TextFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * What to look for (`SearchQuery` of the 2.15.x). A pattern is a case-insensitive substring; a null one
 * is NEUTRAL, never a match. Given both, a file is kept if its name OR its content matches — the
 * screen says "a name OR a content".
 */
data class SearchQuery(
    val root: File,
    val name: String? = null,
    val content: String? = null,
    val maxContentBytes: Long = FileSearch.MAX_CONTENT_BYTES,
    /** The search stops there. */
    val maxResults: Int = FileCaps.SEARCH_RESULTS,
)

data class SearchHit(val path: String, val size: Long, val modifiedMs: Long, val snippet: String?)

sealed interface SearchEvent {
    data class Found(val hits: List<SearchHit>) : SearchEvent

    data class Scanned(val files: Int) : SearchEvent

    /**
     * The last event, as the content search's: what was not searched, said rather than read as "not there" —
     * [nameOnly] text files over [SearchQuery.maxContentBytes] matched on their name alone, [unreadable] files
     * whose content could not be read, [unreadableFolders]; [full], the search stopped at [SearchQuery.maxResults].
     * The 2.15.x stopped at its cap without a word (inventory F14).
     */
    data class Finished(val scanned: Int, val nameOnly: Int, val unreadable: Int, val unreadableFolders: Int, val full: Boolean) :
        SearchEvent
}

/**
 * The global search (`global_search_service.dart`): a walk of [SearchQuery.root] ([FolderWalk]) that never
 * follows a link, skips hidden folders — `DCIM/.thumbnails` holds one picture per photo — and goes on past
 * a folder it cannot read (`Android/data` on Android 11+), which in the 2.15.x first ended the search.
 * Cancelled by cancelling its collector.
 */
object FileSearch {
    /** A larger text file is matched on its name only (2.15.x: 2 MiB). */
    const val MAX_CONTENT_BYTES = FileCaps.SEARCH_CONTENT

    fun run(query: SearchQuery): Flow<SearchEvent> = flow {
        val matcher = Matcher(query)
        val batches = SearchBatches<SearchHit>({ emit(SearchEvent.Found(it)) }, { emit(SearchEvent.Scanned(it)) })
        val walk = FolderWalk(query.root, skipHidden = true)
        walk.forEachFile { path, attributes ->
            matcher.match(path, attributes)?.let(batches::add)
            batches.report(matcher.scanned)
            !matcher.full
        }
        batches.finish(matcher.scanned)
        emit(SearchEvent.Finished(matcher.scanned, matcher.nameOnly, matcher.unreadable, walk.unreadableFolders, matcher.full))
    }

    private class Matcher(private val query: SearchQuery) {
        private val name = query.name?.lowercase()
        private val content = query.content?.lowercase()

        /** Neither pattern (the screen asks for one): every file is kept. */
        private val noCriteria = name == null && content == null
        var scanned = 0
            private set
        var nameOnly = 0
            private set
        var unreadable = 0
            private set
        private var hits = 0

        val full: Boolean get() = hits >= query.maxResults

        /** The hit [path] makes, if any. */
        fun match(path: Path, attributes: BasicFileAttributes): SearchHit? {
            scanned++
            val fileName = path.fileName?.toString().orEmpty()
            val nameMatches = name != null && name in fileName.lowercase()
            val snippet = if (content != null && isText(fileName)) contentOf(path, attributes, content) else null
            if (!nameMatches && snippet == null && !noCriteria) return null
            hits++
            return SearchHit(path.toString(), attributes.size(), attributes.lastModifiedTime().toMillis(), snippet)
        }

        private fun isText(fileName: String): Boolean = FileNames.extension(fileName) in TextFiles.EXTENSIONS

        /** The snippet of a text file's content holding [pattern] — none, counted, when the file is too large for it. */
        private fun contentOf(path: Path, attributes: BasicFileAttributes, pattern: String): String? {
            if (attributes.size() > query.maxContentBytes) {
                nameOnly++
                return null
            }
            return snippetOf(path, pattern)
        }

        /** The first line holding [pattern], cut at 200 characters. */
        private fun snippetOf(path: Path, pattern: String): String? = try {
            TextContent.read(path, query.maxContentBytes).lineSequence().firstOrNull { pattern in it.lowercase() }
                ?.let { if (it.length > SNIPPET_CHARS) it.take(SNIPPET_CHARS) + "…" else it }
        } catch (expected: IOException) {
            unreadable++
            null
        }
    }

    private const val SNIPPET_CHARS = 200
}

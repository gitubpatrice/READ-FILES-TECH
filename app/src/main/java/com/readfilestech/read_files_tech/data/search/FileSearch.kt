package com.readfilestech.read_files_tech.data.search

import com.readfilestech.read_files_tech.data.browse.FileNames
import com.readfilestech.read_files_tech.data.files.FileCaps
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
    val maxResults: Int = FileSearch.MAX_RESULTS,
)

data class SearchHit(val path: String, val size: Long, val modifiedMs: Long, val snippet: String?)

sealed interface SearchEvent {
    data class Found(val hits: List<SearchHit>) : SearchEvent

    data class Scanned(val files: Int) : SearchEvent
}

/**
 * The global search (`global_search_service.dart`): a walk of [SearchQuery.root] ([FolderWalk]) that never
 * follows a link, skips hidden folders — `DCIM/.thumbnails` holds one picture per photo — and goes on past
 * a folder it cannot read (`Android/data` on Android 11+), which in the 2.15.x first ended the search.
 * Cancelled by cancelling its collector.
 */
object FileSearch {
    /** The 2.15.x `_textExts`: the only files whose content is read. */
    val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "xml", "json", "html", "htm", "css", "js", "php", "dart", "yaml", "yml", "ini", "conf", "log",
        "tsv", "rst", "tex", "sh", "py", "java", "kt",
    )

    /** A larger text file is matched on its name only (2.15.x: 2 MiB). */
    const val MAX_CONTENT_BYTES = FileCaps.SEARCH_CONTENT

    /** The search stops there (2.15.x). */
    const val MAX_RESULTS = 1_000

    fun run(query: SearchQuery): Flow<SearchEvent> = flow {
        val matcher = Matcher(query)
        val batches = SearchBatches<SearchHit>({ emit(SearchEvent.Found(it)) }, { emit(SearchEvent.Scanned(it)) })
        FolderWalk(query.root, skipHidden = true).forEachFile { path, attributes ->
            matcher.match(path, attributes)?.let(batches::add)
            batches.report(matcher.scanned)
            !matcher.full
        }
        batches.finish(matcher.scanned)
    }

    private class Matcher(private val query: SearchQuery) {
        private val name = query.name?.lowercase()
        private val content = query.content?.lowercase()

        /** Neither pattern (the screen asks for one): every file is kept. */
        private val noCriteria = name == null && content == null
        var scanned = 0
            private set
        private var hits = 0

        val full: Boolean get() = hits >= query.maxResults

        /** The hit [path] makes, if any. */
        fun match(path: Path, attributes: BasicFileAttributes): SearchHit? {
            scanned++
            val fileName = path.fileName?.toString().orEmpty()
            val nameMatches = name != null && name in fileName.lowercase()
            val snippet = if (content != null && contentReadable(fileName, attributes)) snippetOf(path, content) else null
            if (!nameMatches && snippet == null && !noCriteria) return null
            hits++
            return SearchHit(path.toString(), attributes.size(), attributes.lastModifiedTime().toMillis(), snippet)
        }

        private fun contentReadable(fileName: String, attributes: BasicFileAttributes): Boolean =
            FileNames.extension(fileName) in TEXT_EXTENSIONS && attributes.size() <= query.maxContentBytes

        /** The first line holding [pattern], cut at 200 characters. */
        private fun snippetOf(path: Path, pattern: String): String? = try {
            TextContent.read(path, query.maxContentBytes).lineSequence().firstOrNull { pattern in it.lowercase() }
                ?.let { if (it.length > SNIPPET_CHARS) it.take(SNIPPET_CHARS) + "…" else it }
        } catch (expected: IOException) {
            null
        }
    }

    private const val SNIPPET_CHARS = 200
}

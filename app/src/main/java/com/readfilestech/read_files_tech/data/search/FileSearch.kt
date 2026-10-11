package com.readfilestech.read_files_tech.data.search

import com.readfilestech.read_files_tech.data.browse.FileNames
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
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
 * The global search (`global_search_service.dart`): a walk of [SearchQuery.root] that never follows a
 * link, skips hidden folders — `DCIM/.thumbnails` holds one picture per photo — and goes on past a
 * folder it cannot read (`Android/data` on Android 11+), which in the 2.15.x first ended the search.
 * Cancelled by cancelling its collector.
 */
object FileSearch {
    /** The 2.15.x `_textExts`: the only files whose content is read. */
    val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "xml", "json", "html", "htm", "css", "js", "php", "dart", "yaml", "yml", "ini", "conf", "log",
        "tsv", "rst", "tex", "sh", "py", "java", "kt",
    )

    /** A larger text file is matched on its name only (2.15.x: 2 MiB). */
    const val MAX_CONTENT_BYTES = 2L * 1024 * 1024

    /** The search stops there (2.15.x). */
    const val MAX_RESULTS = 1_000

    fun run(query: SearchQuery): Flow<SearchEvent> = flow {
        val walk = Walk(query)
        val reporter = Reporter(this)
        val folders = ArrayDeque<Path>().apply { add(query.root.toPath()) }
        while (folders.isNotEmpty() && !walk.full) {
            currentCoroutineContext().ensureActive()
            for (child in walk.children(folders.removeFirst())) {
                walk.visit(child)?.let(folders::addLast)
                reporter.report(walk)
                if (walk.full) break
            }
        }
        reporter.finish(walk)
    }

    /** Hits in batches (20, or every 200 ms), progress every 200 files: the screen redraws in steps. */
    private class Reporter(private val out: FlowCollector<SearchEvent>) {
        private var lastFlush = System.currentTimeMillis()
        private var reported = 0

        suspend fun report(walk: Walk) {
            if (walk.scanned - reported >= PROGRESS_EVERY) {
                reported = walk.scanned
                out.emit(SearchEvent.Scanned(reported))
            }
            val late = System.currentTimeMillis() - lastFlush > FLUSH_MS
            if (walk.pending.size >= BATCH || (walk.pending.isNotEmpty() && late)) flush(walk)
        }

        suspend fun finish(walk: Walk) {
            if (walk.pending.isNotEmpty()) flush(walk)
            out.emit(SearchEvent.Scanned(walk.scanned))
        }

        private suspend fun flush(walk: Walk) {
            out.emit(SearchEvent.Found(walk.pending.toList()))
            walk.pending.clear()
            lastFlush = System.currentTimeMillis()
        }
    }

    private class Walk(private val query: SearchQuery) {
        private val name = query.name?.lowercase()
        private val content = query.content?.lowercase()

        /** Neither pattern (the screen asks for one): every file is kept. */
        private val noCriteria = name == null && content == null
        val pending = mutableListOf<SearchHit>()
        var scanned = 0
            private set
        private var hits = 0

        val full: Boolean get() = hits >= query.maxResults

        /** A folder's entries; one that cannot be read gives none, and the walk goes on. */
        fun children(dir: Path): List<Path> = try {
            Files.newDirectoryStream(dir).use(DirectoryStream<Path>::toList)
        } catch (expected: IOException) {
            emptyList()
        } catch (expected: SecurityException) {
            emptyList()
        }

        /** Looks at one entry: returns it if it is a folder to go into; a hit joins [pending]. */
        fun visit(path: Path): Path? {
            val attributes = attributesOf(path) ?: return null
            val fileName = path.fileName?.toString().orEmpty()
            return when {
                // Hidden below the root only: a root the user chose inside a hidden folder is searched.
                fileName.startsWith('.') -> null
                attributes.isDirectory -> path
                else -> {
                    if (attributes.isRegularFile) match(path, fileName, attributes)
                    null
                }
            }
        }

        private fun attributesOf(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (expected: IOException) {
            // Gone meanwhile: skipped.
            null
        }

        private fun match(path: Path, fileName: String, attributes: BasicFileAttributes) {
            scanned++
            val nameMatches = name != null && name in fileName.lowercase()
            val snippet = if (content != null && contentReadable(fileName, attributes)) snippetOf(path, content) else null
            if (nameMatches || snippet != null || noCriteria) {
                hits++
                pending += SearchHit(path.toString(), attributes.size(), attributes.lastModifiedTime().toMillis(), snippet)
            }
        }

        private fun contentReadable(fileName: String, attributes: BasicFileAttributes): Boolean =
            FileNames.extension(fileName) in TEXT_EXTENSIONS && attributes.size() <= query.maxContentBytes

        /** The first line holding [pattern], cut at 200 characters. */
        private fun snippetOf(path: Path, pattern: String): String? = try {
            firstLineWith(path, pattern)?.let { if (it.length > SNIPPET_CHARS) it.take(SNIPPET_CHARS) + "…" else it }
        } catch (expected: IOException) {
            null
        }

        /** Malformed bytes are REPLACED: the 2.15.x decoded strict UTF-8 and silently skipped any Latin-1 text. */
        private fun firstLineWith(path: Path, pattern: String): String? {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
            return InputStreamReader(Files.newInputStream(path), decoder).buffered().use { reader ->
                generateSequence { reader.readLine() }.firstOrNull { pattern in it.lowercase() }
            }
        }
    }

    private const val BATCH = 20
    private const val FLUSH_MS = 200L
    private const val PROGRESS_EVERY = 200
    private const val SNIPPET_CHARS = 200
}

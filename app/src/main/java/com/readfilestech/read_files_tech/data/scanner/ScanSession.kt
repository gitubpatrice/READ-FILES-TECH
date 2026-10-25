package com.readfilestech.read_files_tech.data.scanner

import com.readfilestech.read_files_tech.data.DartJson
import kotlin.math.roundToLong

/** Where a page's photo is: a photo the camera app took, kept in the session's folder, or a file of the storage. */
sealed interface PageSource {
    /** A file of the session's folder, by its name (never a path: nothing outside the folder is ever deleted). */
    data class Owned(val name: String) : PageSource

    /** An image the user imported, read where it is and never deleted. */
    data class External(val path: String) : PageSource
}

/** How one page is made from its photo: the frame, the quarter turns, the filter. */
data class PageSettings(val quad: List<Point>, val turns: Int, val filter: PageFilter) {
    init {
        require(PageGeometry.isUsable(quad)) { "not a usable frame" }
    }
}

/**
 * One page of a scan: its photo, how it is made, and the [revision] of its processed JPEG
 * (`page-<id>-<revision>.jpg` in the session's folder): a page made again gets a new file, so a thumbnail
 * or a PDF never reads a half-written one.
 */
data class ScanPage(val id: String, val source: PageSource, val settings: PageSettings, val revision: Int)

/** The crop screen left open: a photo being framed, for a new page ([page] null) or an existing one. */
data class ScanDraft(val page: String?, val source: PageSource, val settings: PageSettings)

/**
 * A scan in progress: its pages, in order, the filter new pages start with, and the crop screen if one is
 * open. Kept in its folder as JSON after every change, so that it survives the death of the process
 * (the folder's name is the only thing the screen's saved state holds).
 */
data class ScanSession(
    val id: String,
    val pages: List<ScanPage> = emptyList(),
    val filter: PageFilter = PageFilter.ORIGINAL,
    val draft: ScanDraft? = null,
) {
    init {
        require(pages.size <= MAX_PAGES) { "too many pages" }
    }

    val isFull: Boolean get() = pages.size >= MAX_PAGES

    /** [page] added last, or put in the place of the page with the same id (made again). */
    fun withPage(page: ScanPage): ScanSession {
        val at = pages.indexOfFirst { it.id == page.id }
        return copy(pages = if (at >= 0) pages.toMutableList().also { it[at] = page } else pages + page)
    }

    /** The page at [from] moved to [to]; an index out of range changes nothing. */
    fun moved(from: Int, to: Int): ScanSession {
        if (from !in pages.indices || to !in pages.indices || from == to) return this
        val list = pages.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(pages = list)
    }

    fun without(pageId: String): ScanSession = copy(pages = pages.filterNot { it.id == pageId })

    fun toJson(): String = DartJson.encode(
        linkedMapOf(
            "v" to FORMAT,
            "id" to id,
            "filter" to filter.name,
            "pages" to pages.map { page ->
                linkedMapOf("id" to page.id, "revision" to page.revision.toLong()) + sourceJson(page.source) + settingsJson(page.settings)
            },
            "draft" to draft?.let { d -> linkedMapOf<String, Any?>("page" to d.page) + sourceJson(d.source) + settingsJson(d.settings) },
        ),
    )

    companion object {
        /** The 2.15.3's ML Kit scanner took 1 to 25 pages. */
        const val MAX_PAGES = 25
        internal const val FORMAT = 1L

        /** Quadrilaterals are stored in millionths of the photo's sides: integers, so the JSON stays exact. */
        internal const val UNIT = 1_000_000.0

        private val ID = Regex("^[0-9a-f]{16}$")

        /** An owned photo's name: what [ScanFiles] gives it. */
        private val OWNED_NAME = Regex("^src-[0-9a-f]{16}\\.jpg$")

        fun isId(text: String): Boolean = ID.matches(text)

        fun isOwnedName(text: String): Boolean = OWNED_NAME.matches(text)

        private fun sourceJson(source: PageSource): Map<String, Any?> = when (source) {
            is PageSource.Owned -> mapOf("own" to source.name)
            is PageSource.External -> mapOf("path" to source.path)
        }

        private fun settingsJson(s: PageSettings): Map<String, Any?> = linkedMapOf(
            "quad" to s.quad.flatMap { listOf((it.x * UNIT).roundToLong(), (it.y * UNIT).roundToLong()) },
            "turns" to s.turns.toLong(),
            "filter" to s.filter.name,
        )

        /** The session [json] describes, or null (see [SessionReader.read]). */
        fun fromJson(json: String): ScanSession? = SessionReader.read(json)
    }
}

/** Reads a session's JSON, checking whatever it names before it can name a file. */
private object SessionReader {
    /**
     * The session [json] describes, or null when it is not one this app wrote: an id or a name of
     * another shape, a path that is not absolute, a frame that cannot be straightened, too many pages.
     * Whatever comes from a file is checked before it can name a file to read or delete.
     */
    fun read(json: String): ScanSession? = try {
        val root = DartJson.decode(json) as? Map<*, *> ?: invalid()
        need(root["v"] == ScanSession.FORMAT)
        ScanSession(
            id = root.text("id").also { need(ScanSession.isId(it)) },
            pages = (root["pages"] as? List<*> ?: invalid()).map { page(it as? Map<*, *> ?: invalid()) },
            filter = filterOf(root.text("filter")),
            draft = root["draft"]?.let { draft(it as? Map<*, *> ?: invalid()) },
        ).takeIf { s -> s.pages.map(ScanPage::id).toSet().size == s.pages.size }
    } catch (expected: DartJson.Malformed) {
        null
    } catch (expected: Invalid) {
        null
    } catch (expected: IllegalArgumentException) {
        // A frame that cannot be straightened, too many pages: the constructors' require.
        null
    }

    /** A session file this app did not write. */
    private class Invalid : Exception("not a scan session") {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun invalid(): Nothing = throw Invalid()

    private fun need(condition: Boolean) {
        if (!condition) invalid()
    }

    private fun Map<*, *>.text(key: String): String = this[key] as? String ?: invalid()

    private fun Map<*, *>.number(key: String): Long = this[key] as? Long ?: invalid()

    private fun filterOf(name: String): PageFilter = PageFilter.entries.find { it.name == name } ?: invalid()

    private fun page(m: Map<*, *>): ScanPage = ScanPage(
        id = m.text("id").also { need(ScanSession.isId(it)) },
        source = source(m),
        settings = settings(m),
        revision = m.number("revision").also { need(it in 0..Int.MAX_VALUE) }.toInt(),
    )

    private fun draft(m: Map<*, *>): ScanDraft = ScanDraft(
        page = m["page"]?.let { (it as? String)?.takeIf(ScanSession::isId) ?: invalid() },
        source = source(m),
        settings = settings(m),
    )

    private fun source(m: Map<*, *>): PageSource {
        val own = m["own"]
        val path = m["path"]
        return when {
            own is String && path == null && ScanSession.isOwnedName(own) -> PageSource.Owned(own)
            path is String && own == null && path.startsWith("/") && '\u0000' !in path -> PageSource.External(path)
            else -> invalid()
        }
    }

    private fun settings(m: Map<*, *>): PageSettings {
        val numbers = (m["quad"] as? List<*> ?: invalid()).map { it as? Long ?: invalid() }
        need(numbers.size == QUAD_NUMBERS)
        val quad = numbers.chunked(2) { (x, y) -> Point(x / ScanSession.UNIT, y / ScanSession.UNIT) }
        val turns = m.number("turns").also { need(it in 0..MAX_TURN) }.toInt()
        return PageSettings(quad, turns, filterOf(m.text("filter")))
    }

    private const val QUAD_NUMBERS = 8
    private const val MAX_TURN = 3
}

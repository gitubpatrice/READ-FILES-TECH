package com.readfilestech.read_files_tech.data.viewer.reader

import com.readfilestech.read_files_tech.data.files.CappedInputStream
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.document.DocumentXml
import com.readfilestech.read_files_tech.data.viewer.document.XmlEvents
import com.readfilestech.read_files_tech.data.viewer.sheet.TextBudget
import com.readfilestech.read_files_tech.data.viewer.sheet.cut
import com.readfilestech.read_files_tech.data.viewer.sheet.damagedAsUnreadable
import com.readfilestech.read_files_tech.data.viewer.zip.ZipArchive
import com.readfilestech.read_files_tech.data.viewer.zip.ZipItem
import com.readfilestech.read_files_tech.data.viewer.zip.ZipPaths
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.Reader
import java.util.concurrent.atomic.AtomicReferenceArray

/** What bounds the reading of a book; the 2.15.3's caps and the port's, smaller ones in tests only. */
internal data class EpubLimits(
    val fileBytes: Long = FileCaps.EPUB_FILE,
    val chapterBytes: Long = FileCaps.EPUB_CHAPTER,
    val packageBytes: Long = FileCaps.EPUB_PACKAGE,
    val decompressedBytes: Long = FileCaps.EPUB_DECOMPRESSED,
    val items: Int = FileCaps.EPUB_ITEMS,
    val textChars: Long = FileCaps.READER_TEXT_CHARS,
    val stretchChars: Long = FileCaps.XML_STRETCH_CHARS,
    val depth: Int = FileCaps.XML_DEPTH,
    val elements: Int = FileCaps.XML_ELEMENTS,
)

/**
 * An EPUB book, 2 or 3, opened for the reader (`ReaderService.readEpub` of the 2.15.3): `container.xml`
 * names the package document (OPF), whose spine orders the chapters; their titles come from the book's
 * table of contents — its EPUB 3 nav, else its EPUB 2 NCX. Opening reads only these. A chapter is read the
 * first time it is shown, once, however often the spine names it, and kept while the text cap allows — the
 * 2.15.3 read them all at once, on the UI thread, a chapter the spine repeated each time (inventory D22).
 *
 * Read through the port's [ZipArchive] (sizes checked, each entry by its own record); the package parts as
 * XML with [DocumentXml]'s guards (no DTD); the chapters and the table of contents — XHTML that may name a
 * DTD and use its entities — with [HtmlTokenizer], which resolves none. Open until [close]: the archive.
 */
class EpubBook private constructor(private val parts: BookParts, private val chapters: List<ZipItem>, titles: Array<String?>) :
    ReaderDocument {
    override val chapterCount: Int get() = chapters.size

    /** The table of contents' titles, then the first headings found as chapters are read. */
    private val titles = AtomicReferenceArray(titles)
    private val lock = Mutex()

    /** The chapters read, the one read least recently first; guarded by [lock], as [kept]. */
    private val cache = LinkedHashMap<Int, ReaderChapter>(INITIAL_CACHE, LOAD_FACTOR, true)
    private var kept = 0L

    override fun title(index: Int): String = titles[index] ?: fileTitle(chapters[index])

    override suspend fun chapter(index: Int): ReaderChapter = lock.withLock {
        cache[index] ?: read(index).also { keep(index, it) }
    }

    private suspend fun read(index: Int): ReaderChapter {
        val limits = parts.limits
        val blocks = parts.text(chapters[index], limits.chapterBytes) { text ->
            BlockReader(ReadingMode.CHAPTER, TextBudget(limits.textChars), limits.depth).read(HtmlTokenizer(text))
        }
        blocks.firstHeading?.let { titles.compareAndSet(index, null, cut(it, TITLE_CHARS)) }
        return ReaderChapter(title(index), blocks)
    }

    /** Keeps [chapter] within the text cap: the chapters read least recently go first, never the one just read. */
    private fun keep(index: Int, chapter: ReaderChapter) {
        cache[index] = chapter
        kept += chapter.blocks.cost
        val eldest = cache.entries.iterator()
        while (kept > parts.limits.textChars && cache.size > 1) {
            kept -= eldest.next().value.blocks.cost
            eldest.remove()
        }
    }

    override fun close() = parts.archive.close()

    companion object {
        /** The longest title kept: a menu shows one line of it. */
        internal const val TITLE_CHARS = 200
        private const val CONTAINER = "META-INF/container.xml"
        private const val ENCRYPTION = "META-INF/encryption.xml"
        private const val INITIAL_CACHE = 16
        private const val LOAD_FACTOR = 0.75f

        /** Reads [source]'s package within the caps; its chapters are read when shown. */
        suspend fun open(source: ViewerSource): EpubBook = open(source, EpubLimits())

        internal suspend fun open(source: ViewerSource, limits: EpubLimits): EpubBook {
            val archive = ZipArchive.open(source, limits.fileBytes)
            var book: EpubBook? = null
            try {
                book = read(BookParts(archive, limits))
                return book
            } finally {
                if (book == null) archive.close()
            }
        }

        private suspend fun read(parts: BookParts): EpubBook {
            val encryption = parts[ENCRYPTION]
            if (encryption != null && parts.xml(encryption, EpubPackage::protects)) throw UnsupportedFormatException("encrypted: a DRM")
            val packageName = packageName(parts)
            val book = parts.xml(checkNotNull(parts[packageName])) { EpubPackage.read(it, parts.limits.items) }
            val folder = EpubPaths.folderOf(packageName)
            // A file the spine names twice, by one id or by two, is one chapter.
            val chapters = LinkedHashSet<ZipItem>()
            for (idref in book.spine) {
                val item = book.manifest[idref]?.takeIf(EpubPackage.Item::isDocument) ?: continue
                EpubPaths.resolve(folder, item.href)?.let { parts[it] }?.let(chapters::add)
            }
            val spine = chapters.toList()
            val titles = arrayOfNulls<String>(spine.size)
            book.contents()?.let { contents ->
                val name = EpubPaths.resolve(folder, contents.href) ?: return@let
                parts[name]?.let { parts.titles(it, EpubPaths.folderOf(name), contents.isNcx, spine, titles) }
            }
            return EpubBook(parts, spine, titles)
        }

        /** The entry of the package document `container.xml` names: no container, or none there, is no EPUB. */
        private suspend fun packageName(parts: BookParts): String {
            val container = parts[CONTAINER] ?: throw UnreadableFileException("no META-INF/container.xml: not an EPUB")
            val name = parts.xml(container, EpubPackage::packagePath)?.let { EpubPaths.resolve("", it) }
            if (name == null || parts[name] == null) throw UnreadableFileException("container.xml names no package there is")
            return name
        }

        /** The 2.15.3's last resort: the file's name, without its folders nor its extension. */
        private fun fileTitle(item: ZipItem): String = ZipPaths.display(item.name.substringAfterLast('/').substringBeforeLast('.'))
    }
}

/** The archive of a book, its entries by name, read within its caps — every part's bytes spent from one budget. */
internal class BookParts(val archive: ZipArchive, val limits: EpubLimits) {
    private val byName = HashMap<String, ZipItem>()
    private val ambiguous = HashSet<String>()

    /** Bytes decompressed so far, against [EpubLimits.decompressedBytes]. */
    private var spent = 0L

    init {
        archive.entries.filterNot(ZipItem::isDirectory).forEach { item ->
            if (byName.put(key(item), item) != null) ambiguous += key(item)
        }
    }

    /** The entry named [name], or null; two of that name make the book unreadable, never one read by chance. */
    operator fun get(name: String): ZipItem? {
        if (name in ambiguous) throw UnreadableFileException("two entries named $name")
        return byName[name]
    }

    /** Reads [item] as XML: a package part, refused past [EpubLimits.packageBytes] before a byte is inflated. */
    suspend fun <T> xml(item: ZipItem, read: suspend (XmlEvents) -> T): T {
        spend(item, limits.packageBytes)
        return damagedAsUnreadable {
            archive.read(item, limits.packageBytes).use { input ->
                read(XmlEvents(DocumentXml.parser(input, limits.stretchChars), limits.depth, limits.elements))
            }
        }
    }

    /** Reads [item] as XHTML characters (UTF-8 or UTF-16), refused past [cap] before a byte is inflated. */
    suspend fun <T> text(item: ZipItem, cap: Long, read: suspend (Reader) -> T): T {
        spend(item, cap)
        return damagedAsUnreadable { archive.read(item, cap).use { input -> read(DocumentXml.reader(input)) } }
    }

    /**
     * Into [titles], the titles the table of contents [item] — in [folder]; an NCX when [ncx], else an EPUB 3
     * nav — gives the chapters of [spine]: those read before anything goes wrong in it, the first for each. A
     * table of contents too large or damaged leaves the other chapters to their headings, never the book unread.
     */
    suspend fun titles(item: ZipItem, folder: String, ncx: Boolean, spine: List<ZipItem>, titles: Array<String?>) {
        val chapterOf = HashMap<String, Int>()
        spine.forEachIndexed { index, chapter -> chapterOf[key(chapter)] = index }
        val contents = EpubContents { href, title ->
            val chapter = EpubPaths.resolve(folder, href)?.let(chapterOf::get)
            if (chapter != null && titles[chapter] == null) titles[chapter] = cut(title, EpubBook.TITLE_CHARS)
        }
        try {
            spend(minOf(item.size, limits.packageBytes))
            damagedAsUnreadable {
                // Read up to the cap whatever it declares, then a quiet end: the titles read stay.
                CappedInputStream(archive.readStart(item, item.size), limits.packageBytes).use { input ->
                    val tokens = HtmlTokenizer(DocumentXml.reader(input), EpubContents.ATTRIBUTES)
                    if (ncx) contents.readNcx(tokens) else contents.readNav(tokens)
                }
            }
        } catch (expected: IOException) {
            // The titles read so far stay; the others come from the chapters' headings.
        }
    }

    private fun spend(item: ZipItem, cap: Long) {
        if (item.size > cap) throw FileTooLargeException(cap)
        spend(item.size)
    }

    private fun spend(bytes: Long) {
        spent += bytes
        if (spent > limits.decompressedBytes) throw FileTooLargeException(limits.decompressedBytes)
    }

    /** An entry's name as references resolve to it: no leading `/`. */
    private fun key(item: ZipItem): String = item.name.trimStart('/')
}

/** What a chapter costs of [FileCaps.READER_TEXT_CHARS] while it is kept. */
internal val ReaderBlocks.cost: Long get() = chars.toLong() + size.toLong() * BlockReader.BLOCK_CHARGE

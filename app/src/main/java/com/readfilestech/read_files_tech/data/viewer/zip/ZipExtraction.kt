package com.readfilestech.read_files_tech.data.viewer.zip

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileMoves
import com.readfilestech.read_files_tech.data.files.FileTrees
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

/** Why an entry was not extracted. */
sealed interface NotExtracted {
    /** It cannot be read at all, or its data did not hold when it was. */
    data class Unreadable(val problem: ZipProblem) : NotExtracted

    /** Its name leads outside the folder, or names no file ([ZipPaths.segments]). */
    data object UnsafeName : NotExtracted

    /**
     * Another entry already took its name — the same, or the same but for case (the shared storage of
     * Android ignores case), a folder against a file — or something else is in the way.
     */
    data object NameTaken : NotExtracted

    /** Larger, by its declaration, than what the total of [FileCaps.ZIP_EXTRACT_TOTAL] still leaves. */
    data object OverTotal : NotExtracted
}

/** How an extraction stopped before its last entry, when it did not end by itself. */
sealed interface ExtractionStop {
    /** [FileCaps.ZIP_EXTRACT_TOTAL] is written: the entries after are not read. */
    data object TotalReached : ExtractionStop

    /** The storage refused a write (full, removed, read-only), or the archive could not be read any more. */
    data class Failed(val error: IOException) : ExtractionStop
}

/**
 * What an extraction did, kept as it goes so that it is known however it ends — finished, stopped, or
 * cancelled half-way: the files written are whole and stay, the one in hand when it stopped is gone.
 */
class ExtractionReport(val folder: File) {
    var written: Int = 0
        private set
    var bytes: Long = 0
        private set
    private val leftOut = mutableListOf<Pair<ZipItem, NotExtracted>>()

    /** The entries left out, in the archive's order, and why. */
    val refused: List<Pair<ZipItem, NotExtracted>> get() = leftOut

    var stop: ExtractionStop? = null
        internal set

    internal fun wrote(size: Long) {
        written++
        bytes += size
    }

    internal fun refuse(item: ZipItem, reason: NotExtracted) {
        leftOut += item to reason
    }
}

/**
 * "Extract all" (`extractArchive` of the 2.15.3): every entry of [archive] into [folder] — a new, empty
 * folder ([com.readfilestech.read_files_tech.data.outputs.OutputStorage.reserveFolder]) — and nothing
 * anywhere else.
 *
 * Each entry is refused on its own, said in the report, the others go on: a name that leaves the folder
 * (refused before a byte is read), an entry that cannot be read, one whose data lies or is cut, a name
 * already taken. Every folder is created by this extraction, as a real folder; every file by an
 * exclusive creation that follows no link, inside a folder whose canonical path is checked first. A
 * file is written beside its name under a hidden temporary one and published by a rename once whole and
 * verified: neither a failure nor a cancellation leaves a half file under an entry's name.
 *
 * Bounded by what is produced, never by what is declared: [entryCap] per entry, [totalCap] for all
 * (the 2.15.3's 200 MiB and 1 GiB). A write that fails stops it — the next would fail alike.
 */
class ZipExtraction(
    private val archive: ZipArchive,
    private val folder: File,
    private val entryCap: Long = FileCaps.ZIP_ENTRY_DECOMPRESSED,
    private val totalCap: Long = FileCaps.ZIP_EXTRACT_TOTAL,
) {
    val report = ExtractionReport(folder)

    /**
     * Folders made, by their path ignoring case (Android's shared storage does): two spellings, one
     * folder. In the order they were made, a folder before what it holds.
     */
    private val folders = LinkedHashMap<String, File>()

    /** Files written, by the same key. */
    private val files = HashSet<String>()
    private lateinit var root: Path

    /** Runs it, [onEntry] told after each entry how many are done and how many bytes are written. */
    suspend fun run(onEntry: (done: Int, bytes: Long) -> Unit = { _, _ -> }) {
        root = folder.toPath().toRealPath()
        try {
            for ((i, item) in archive.entries.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!item.isDirectory && report.bytes >= totalCap) {
                    report.stop = ExtractionStop.TotalReached
                    return
                }
                extract(item)
                onEntry(i + 1, report.bytes)
            }
        } catch (e: IOException) {
            report.stop = ExtractionStop.Failed(e)
        }
    }

    /**
     * Removes the folder when no file was written in it — after an archive of encrypted entries, a
     * cancellation before the first file: the folders the extraction made, deepest first, then the
     * folder itself, each only while empty. True when it is gone.
     */
    fun discardIfEmpty(): Boolean {
        if (report.written > 0) return false
        folders.values.reversed().forEach { runCatching { Files.delete(it.toPath()) } }
        runCatching { Files.delete(folder.toPath()) }
        return !FileTrees.exists(folder)
    }

    private suspend fun extract(item: ZipItem) {
        val segments = item.path
        val refusal = when {
            segments == null -> NotExtracted.UnsafeName
            item.isDirectory -> NotExtracted.NameTaken.takeIf { folderFor(segments) == null }
            else -> refusalBefore(item, segments) ?: writeInto(item, segments)
        }
        refusal?.let { report.refuse(item, it) }
    }

    /** Why [item] is refused before a byte of it is read, nor a folder made for it; null when it may be. */
    private fun refusalBefore(item: ZipItem, segments: List<String>): NotExtracted? = when {
        item.problem != null -> NotExtracted.Unreadable(item.problem)
        item.size > entryCap -> NotExtracted.Unreadable(ZipProblem.TOO_LARGE)
        // What is left of the total bounds this entry too: the last one cannot carry it past the total.
        item.size > totalCap - report.bytes -> NotExtracted.OverTotal
        keyOf(segments).let { it in files || it in folders } -> NotExtracted.NameTaken
        else -> null
    }

    /** Writes [item] at [segments], its folders made first: null when written, why not otherwise. */
    private suspend fun writeInto(item: ZipItem, segments: List<String>): NotExtracted? {
        val parent = folderFor(segments.dropLast(1))
        return when {
            parent == null -> NotExtracted.NameTaken
            // The folder as the system resolves it now, links followed: inside, whatever was swapped under
            // it meanwhile (toRealPath: File.canonicalFile follows no link on Windows, where tests run too).
            !parent.toPath().toRealPath().startsWith(root) -> NotExtracted.UnsafeName
            else -> write(item, File(parent, segments.last()), keyOf(segments))
        }
    }

    private suspend fun write(item: ZipItem, target: File, key: String): NotExtracted? = try {
        report.wrote(ZipFiles.write(archive, item, target, minOf(entryCap, totalCap - report.bytes)))
        files += key
        null
    } catch (e: ZipEntryException) {
        NotExtracted.Unreadable(e.problem)
    } catch (expected: FileAlreadyExistsException) {
        // The volume ignores case where the key did not see it (Unicode folding), or another app.
        NotExtracted.NameTaken
    }

    /**
     * The folder at [segments] under the root, made if missing, one level at a time: null when a FILE
     * holds one of its names, or something that is not a real folder (a link) is in the way.
     */
    private fun folderFor(segments: List<String>): File? {
        var dir: File? = folder
        var depth = 0
        while (dir != null && depth < segments.size) {
            depth++
            val key = keyOf(segments.subList(0, depth))
            dir = when {
                key in files -> null
                else -> folders[key] ?: realFolder(File(dir, segments[depth - 1]))?.also { folders[key] = it }
            }
        }
        return dir
    }

    /**
     * [dir], made — or already there as a real folder: made by a spelling the key missed, or by another
     * app. Null when something else is there, a link to a folder included.
     */
    private fun realFolder(dir: File): File? {
        try {
            Files.createDirectory(dir.toPath())
        } catch (expected: FileAlreadyExistsException) {
            // Judged below, without following a link.
        }
        return dir.takeIf { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
    }

    private fun keyOf(segments: List<String>): String = segments.joinToString("/").lowercase(Locale.ROOT)
}

/** Writing one entry to a file of its own. */
internal object ZipFiles {
    /**
     * Writes [item] as the NEW file [target]: into a hidden temporary beside it ([FileMoves.partBeside]),
     * created exclusively without following a link, then renamed to [target] once whole — the rename
     * refuses a [target] that exists. The temporary is deleted however it ends. Cancellable between
     * pieces of 64 KiB.
     *
     * @return the bytes written.
     * @throws ZipEntryException the entry cannot be read, or its data did not hold (nothing is left).
     * @throws FileAlreadyExistsException something is at [target].
     */
    suspend fun write(archive: ZipArchive, item: ZipItem, target: File, cap: Long): Long {
        val part = FileMoves.partBeside(target)
        try {
            var written = 0L
            Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { out ->
                archive.read(item, cap).use { input -> written = copy(input, out) }
            }
            Files.move(part, target.toPath())
            return written
        } finally {
            // Gone once renamed; the half file of a failure or a cancellation otherwise.
            runCatching { Files.deleteIfExists(part) }
        }
    }

    /** [input] into [out] by pieces of 64 KiB, a cancellation checked between two; the bytes copied. */
    private suspend fun copy(input: InputStream, out: OutputStream): Long {
        val buffer = ByteArray(BUFFER)
        var copied = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n == -1) return copied
            out.write(buffer, 0, n)
            copied += n
        }
    }

    private const val BUFFER = 64 * 1024
}

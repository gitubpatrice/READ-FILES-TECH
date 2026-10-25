package com.readfilestech.read_files_tech.data.tools

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.search.FolderWalk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.MessageDigest

/** A file the finder listed, as it was then. */
data class DuplicateFile(val path: String, val size: Long, val modifiedMs: Long)

/** Files of the same content (SHA-256 [digest]), in the order of their paths. */
data class DuplicateGroup(val digest: String, val files: List<DuplicateFile>) {
    val size: Long get() = files.first().size

    /** What deleting all copies but one would free. */
    val wastedBytes: Long get() = size * (files.size - 1)
}

/**
 * What the finder found: the [groups], the most wasteful first; the [largest] files; how many files it
 * compared, and what it could not read — said, never silently left out. [truncated]: it stopped listing at
 * [FileCaps.DUPLICATE_FILES] files.
 */
data class DuplicateReport(
    val groups: List<DuplicateGroup>,
    val largest: List<DuplicateFile>,
    val filesListed: Int,
    val unreadableFolders: Int,
    val unreadableFiles: Int,
    val truncated: Boolean,
)

/** How far the finder is: listing, then sampling the files of equal sizes, then reading those whose samples agree. */
sealed interface DuplicateProgress {
    data class Listing(val files: Int) : DuplicateProgress

    data class Sampling(val done: Int, val total: Int) : DuplicateProgress

    data class Reading(val done: Int, val total: Int) : DuplicateProgress
}

/**
 * The duplicate finder (`duplicate_finder_service.dart` of the 2.15.3), in its three passes: the files of
 * a folder listed with their sizes — only a size shared can be a duplicate —, then a SHA-256 of their first
 * and last 64 KiB, then a SHA-256 of the whole file, read by pieces, for those whose samples agree.
 *
 * On the searches' walk ([FolderWalk]): a folder that cannot be read is skipped and counted (the 2.15.3's
 * first error ended the whole scan, D9), symbolic links are not followed, and the trash is left out — its
 * copies were listed as duplicates of their originals (D8) —, with `Android/data`, `Android/obb`, hidden
 * entries and `cache` folders (the 2.15.3 left out hidden files, `.thumbnails`, `.trash` and `cache`). Two
 * paths of ONE file (a hard link) are one file: deleting one of them frees nothing. Cancellable between two
 * pieces read.
 */
object DuplicateFinder {
    private const val SAMPLE = 64 * 1024

    /** @throws FileNotFoundException [root] is not a folder (the 2.15.3's "Dossier introuvable"). */
    suspend fun find(
        root: File,
        onProgress: (DuplicateProgress) -> Unit = {},
        minBytes: Long = FileCaps.DUPLICATE_MIN_BYTES,
        maxFiles: Int = FileCaps.DUPLICATE_FILES,
    ): DuplicateReport {
        if (!root.isDirectory) throw FileNotFoundException(root.path)
        val walk = FolderWalk(root, skipHidden = true, skipFolder = ::skipped)
        val listed = mutableListOf<DuplicateFile>()
        val seen = HashSet<Any>()
        var truncated = false
        walk.forEachFile { path, attributes ->
            val key = attributes.fileKey()
            // A second path to a file already listed (a hard link) is not a copy of it.
            if (attributes.size() >= minBytes && (key == null || seen.add(key))) {
                listed += DuplicateFile(path.toString(), attributes.size(), attributes.lastModifiedTime().toMillis())
                if (listed.size % LISTING_EVERY == 0) onProgress(DuplicateProgress.Listing(listed.size))
            }
            truncated = listed.size >= maxFiles
            !truncated
        }
        onProgress(DuplicateProgress.Listing(listed.size))
        val unreadable = Unreadable()
        val sampling = { done: Int, total: Int -> onProgress(DuplicateProgress.Sampling(done, total)) }
        val reading = { done: Int, total: Int -> onProgress(DuplicateProgress.Reading(done, total)) }
        val sampled = refine(listed.groupBy { it.size }.values, unreadable, sampling) { sample(it) }.map { it.second }
        val groups = refine(sampled, unreadable, reading) { digest(it) }
            .map { (digest, files) -> DuplicateGroup(digest, files.sortedBy(DuplicateFile::path)) }
            .sortedWith(byWaste)
        val largest = listed.sortedWith(bySize).take(FileCaps.DUPLICATE_LARGEST)
        return DuplicateReport(groups, largest, listed.size, walk.unreadableFolders, unreadable.count, truncated)
    }

    private class Unreadable {
        var count = 0
    }

    /**
     * Each bucket of two files or more split by [key], read file by file; the buckets stay apart (a size
     * never meets another size). A file that cannot be read is counted and left out.
     */
    private suspend fun refine(
        buckets: Collection<List<DuplicateFile>>,
        unreadable: Unreadable,
        onProgress: (done: Int, total: Int) -> Unit,
        key: suspend (DuplicateFile) -> String,
    ): List<Pair<String, List<DuplicateFile>>> {
        val candidates = buckets.filter { it.size >= 2 }
        val total = candidates.sumOf { it.size }
        var done = 0
        onProgress(0, total)
        return candidates.flatMap { bucket ->
            bucket.mapNotNull { file ->
                currentCoroutineContext().ensureActive()
                val value = try {
                    key(file)
                } catch (expected: IOException) {
                    unreadable.count++
                    null
                }
                onProgress(++done, total)
                value?.let { it to file }
            }.groupBy({ it.first }, { it.second }).filterValues { it.size >= 2 }.toList()
        }
    }

    /** SHA-256 of the first and last 64 KiB — of the first 128 KiB only, or less, in a smaller file (2.15.3). */
    private fun sample(file: DuplicateFile): String = RandomAccessFile(file.path, "r").use { raf ->
        val digest = MessageDigest.getInstance(SHA_256)
        val buffer = ByteArray(SAMPLE)
        digest.update(buffer, 0, raf.readUpTo(buffer, minOf(file.size, SAMPLE.toLong()).toInt()))
        if (file.size > 2L * SAMPLE) {
            raf.seek(file.size - SAMPLE)
            digest.update(buffer, 0, raf.readUpTo(buffer, SAMPLE))
        }
        digest.digest().toHex()
    }

    /** SHA-256 of the whole file, by pieces: never the file in memory (a video of 2 GB). */
    private suspend fun digest(file: DuplicateFile): String {
        val context = currentCoroutineContext()
        return FileHashes.sha256(File(file.path)) { context.ensureActive() }
    }

    private fun RandomAccessFile.readUpTo(buffer: ByteArray, wanted: Int): Int {
        var read = 0
        while (read < wanted) {
            val n = read(buffer, read, wanted - read)
            if (n == -1) break
            read += n
        }
        return read
    }

    /** The apps' folders, then the 2.15.3's `cache` folders; the trash, hidden, goes with every hidden entry (D8). */
    private fun skipped(dir: Path): Boolean = FolderWalk.isAppData(dir) || dir.fileName?.toString() == CACHE

    /** The most wasteful group first; then by path, so that two scans give one order. */
    private val byWaste = compareByDescending<DuplicateGroup> { it.wastedBytes }.thenBy { it.files.first().path }
    private val bySize = compareByDescending<DuplicateFile> { it.size }.thenBy { it.path }

    private const val SHA_256 = "SHA-256"
    private const val CACHE = "cache"
    private const val LISTING_EVERY = 500
}

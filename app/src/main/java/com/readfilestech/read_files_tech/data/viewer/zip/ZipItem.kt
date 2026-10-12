package com.readfilestech.read_files_tech.data.viewer.zip

import com.readfilestech.read_files_tech.data.browse.FileNames
import java.io.IOException

/** Why an entry of an archive cannot be read, whatever is done with it. */
enum class ZipProblem {
    /** Encrypted (ZipCrypto or AES): no password-protected entry is opened. */
    ENCRYPTED,

    /** Compressed by another method than store and deflate (bzip2, LZMA, deflate64…). */
    UNSUPPORTED_METHOD,

    /** A symbolic link: never created, nor written as a file. */
    LINK,

    /**
     * Its data overlaps another entry's: a forged archive (the "better zip bomb" reads one block of
     * data through thousands of entries) or a damaged one.
     */
    OVERLAPPING,

    /** Larger, once decompressed, than an entry may be ([com.readfilestech.read_files_tech.data.files.FileCaps.ZIP_ENTRY_DECOMPRESSED]). */
    TOO_LARGE,

    /** Its record or its data does not hold together: a size, a checksum, a header that lies or is cut. */
    DAMAGED,
}

/** [item] cannot be read, for [problem]. Its text is for the log and the tests, never for the screen. */
class ZipEntryException(val item: ZipItem, val problem: ZipProblem) : IOException("entry ${item.index}: $problem")

/**
 * One record of an archive's central directory — what the archive says of an entry, which a read
 * checks rather than believes: its size, its checksum, where its data is.
 *
 * Two entries may bear one name, and are told apart by [index]: each is read from its own record, never
 * looked up by its name (java.util.zip.ZipFile reads whichever of the two its lookup finds).
 */
class ZipItem internal constructor(
    /** Its place in the central directory, from 0. */
    val index: Int,
    /** The name as stored, decoded, every `\` turned to `/` (Windows PowerShell 5.1 writes `\`). */
    val name: String,
    val isDirectory: Boolean,
    /** As declared: shown in the listing, and a read refuses to go past it. */
    val size: Long,
    /** Why it cannot be read at all, or null. */
    val problem: ZipProblem?,
    internal val data: ZipData,
) {
    /** The name without its folders, as the listing shows it. */
    val displayName: String get() = ZipPaths.display(name.trimEnd('/').substringAfterLast('/'))

    /** The folders the entry is in, `a/b` for `a/b/c.txt`; empty at the top. */
    val folder: String get() = ZipPaths.display(name.trimEnd('/').substringBeforeLast('/', ""))

    /** Its path once extracted, by segments; null when its name would leave the folder ([ZipPaths]). */
    val path: List<String>? get() = ZipPaths.segments(name)

    /**
     * The name it is shared under: the last segment alone, whatever folders precede it — none of them
     * is created (the 2.15.3 also replaced every character outside `[A-Za-z0-9._-]`, so "Relevé été.pdf"
     * left as "Relev__t_.pdf"). Null when that segment is no file name (`..`, a control character).
     */
    val shareName: String? get() = name.trimEnd('/').substringAfterLast('/').takeIf(FileNames::isValid)

    /** Whether it can be read: a file, with no [problem]. */
    val readable: Boolean get() = !isDirectory && problem == null
}

/** Where an entry's data is and how it is stored: what a read needs beside the entry's name and size. */
internal class ZipData(
    val compressedSize: Long,
    val method: Int,
    val crc: Int,
    /** Where its local header is, from the start of the file (data prepended to the archive included). */
    val localOffset: Long,
)

/** Entry names: what may be created from one, and how one is shown. */
object ZipPaths {
    private val DRIVE = Regex("^[A-Za-z]:")

    /**
     * The relative path [name] gives (its `\` already turned to `/`), by segments; null when it would
     * leave the folder it is extracted to or names no file — `safeJoin` of the 2.15.3: empty, absolute,
     * a Windows drive letter, a `..` or `.` segment, a NUL — plus any control character, refused as
     * in a name the user types ([FileNames.isValid]). Empty segments (`a//b`, a folder's trailing `/`)
     * are dropped.
     *
     * Textual only: whatever the folder already holds (a link) is checked when the file is created.
     */
    fun segments(name: String): List<String>? {
        if (name.isEmpty() || name.startsWith('/') || DRIVE.containsMatchIn(name)) return null
        val segments = name.split('/').filter(String::isNotEmpty)
        return segments.takeIf { it.isNotEmpty() && it.all(FileNames::isValid) }
    }

    /** [text] with its control characters shown as `�`: a name may hold a line break, or a NUL. */
    fun display(text: String): String = buildString(text.length) {
        text.forEach { append(if (it.isISOControl()) '�' else it) }
    }
}

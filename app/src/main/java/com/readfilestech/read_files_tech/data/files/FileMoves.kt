package com.readfilestech.read_files_tech.data.files

import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.SecureRandom

/** No free name left in a folder for an item: `name`, `name (1)` … `name (999)` are all taken. */
class NoFreeNameException(message: String) : IOException(message)

/** What tells "the same file, unchanged": its inode (when the volume gives one), size and date. */
internal data class Identity(val key: Any?, val size: Long, val modified: FileTime) {
    companion object {
        fun of(attributes: BasicFileAttributes) = Identity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime())

        fun of(path: Path) = of(Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS))
    }
}

/**
 * Moving and copying items without ever replacing anything: the trash (in and out) and the explorer
 * (copy to, move to) share these, so a guard added here holds for both.
 */
object FileMoves {
    private const val MAX_CANDIDATES = 1_000

    /** The hidden temporary copies (a file or a folder) published by one rename once whole. */
    const val PART_PREFIX = ".rft-part-"
    private val random = SecureRandom()

    /**
     * Moves [from] to [to] — a free path, or the empty reservation of [reserveUniquePath] — never over
     * anything else.
     *
     * A bare rename (`ATOMIC_MOVE`): it replaces a reservation in ONE step. `REPLACE_EXISTING` is not
     * one: libcore unlinks the target, then renames, and a file appearing in between — another restore,
     * another app — would be replaced (Claude review R3). A copy only between two volumes
     * (`AtomicMoveNotSupportedException`, EXDEV), never on another error.
     */
    fun move(from: File, to: File, isDir: Boolean) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (expected: AtomicMoveNotSupportedException) {
            // Another volume (EXDEV): no rename can do it.
            if (isDir) TreeMoves.moveAcross(from.toPath(), to.toPath()) else moveFileAcross(from, to)
        } catch (e: IOException) {
            // A folder renamed over the empty folder that reserves its place: some file systems refuse
            // it (FUSE, Windows). Freed, then renamed WITHOUT replacement — a folder lands on nothing.
            // A reservation no longer empty is not ours to free: Files.delete fails, nothing is lost.
            if (!isDir || !Files.isDirectory(to.toPath(), LinkOption.NOFOLLOW_LINKS)) throw e
            Files.delete(to.toPath())
            Files.move(from.toPath(), to.toPath())
        }
    }

    /**
     * Copies the regular file [from] into [to]: a free path, or the reservation of [reserveUniquePath].
     * The copy is flushed to the device and its size checked before this returns: a caller that deletes
     * the source afterwards never deletes the only complete copy — a full disk, a card pulled out, a
     * source that shrank meanwhile all throw instead.
     */
    fun copyFile(from: File, to: File) {
        // Into a hidden temporary file first, published by one rename once complete: a process killed
        // half-way leaves an empty reservation and a hidden `.rft-part-…`, never a truncated file under
        // the real name that looks whole (Claude review R4). Short name: a long one could pass 255 bytes.
        val part = partBeside(to)
        try {
            copyInto(from.toPath(), part, StandardOpenOption.CREATE_NEW)
            // The rename replaces whatever is at [to]: only our empty reservation, or nothing. Anything
            // else took its place during the copy (another app) and is left alone (Claude review R5).
            if (!isFreeOrEmpty(to)) throw FileAlreadyExistsException(to.path)
            Files.move(part, to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            runCatching { Files.deleteIfExists(part) }
        }
    }

    /**
     * Removes [file] only if it is still an empty reservation — an empty file or an empty folder. What
     * took its place, or what it came to hold, is not ours (Claude review R5): a failed operation calls
     * this, never a recursive delete, on a name it reserved.
     */
    fun discardReservation(file: File) {
        if (isFreeOrEmpty(file)) runCatching { Files.deleteIfExists(file.toPath()) }
    }

    /**
     * A free path in [dir] for [name], CREATED to be reserved: `name`, then `name (1)`, `name (2)`… (the
     * extension kept: `photo (1).jpg`). A file is reserved by an exclusive file, a folder by an exclusive
     * empty folder; the move or copy then replaces that reservation, never anything else.
     *
     * @throws NoFreeNameException no free name.
     */
    fun reserveUniquePath(dir: File, name: String, isDir: Boolean): File {
        for (candidate in candidates(dir, name)) {
            try {
                if (isDir) Files.createDirectory(candidate.toPath()) else Files.createFile(candidate.toPath())
                return candidate
            } catch (expected: FileAlreadyExistsException) {
                // Taken: the next candidate. Any OTHER error (a read-only volume, a missing folder) is
                // thrown as it is — it used to read as "taken", 999 times (Claude review R4).
            }
        }
        throw NoFreeNameException("no free name for ${dir.path}/$name")
    }

    /** A hidden temporary name next to [target], short whatever the target's name. */
    internal fun partBeside(target: File): Path {
        val folder = target.absoluteFile.parentFile ?: throw IOException("no folder for $target")
        return File(folder, "$PART_PREFIX${Integer.toUnsignedString(random.nextInt())}").toPath()
    }

    internal fun copyInto(from: Path, to: Path, vararg modes: StandardOpenOption) {
        FileChannel.open(to, StandardOpenOption.WRITE, *modes).use { out ->
            Files.newInputStream(from).use { input -> input.copyTo(Channels.newOutputStream(out)) }
            out.force(true)
        }
        val copied = Files.size(to)
        val source = Files.size(from)
        if (copied != source) throw IOException("incomplete copy of $from: $copied of $source bytes")
    }

    private fun candidates(dir: File, name: String): Sequence<File> = sequence {
        yield(File(dir, name))
        // `dot > 0`: a hidden file such as `.bashrc` has no extension, all of it is the name.
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1 until MAX_CANDIDATES) yield(File(dir, "$base ($i)$ext"))
    }

    private fun moveFileAcross(from: File, to: File) {
        val before = Identity.of(from.toPath())
        copyFile(from, to)
        // Deleted only if it is still the file that was copied: renamed and replaced, or rewritten in
        // place meanwhile, it is kept, and the caller removes the copy (GPT review R4).
        if (Identity.of(from.toPath()) != before) throw IOException("$from changed during the copy: the source is kept")
        Files.delete(from.toPath())
    }

    /** Nothing at [file], or an empty regular file, or an empty folder — a link is never "empty". */
    internal fun isFreeOrEmpty(file: File): Boolean {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return when {
            attributes.isRegularFile -> attributes.size() == 0L
            attributes.isDirectory -> file.list()?.isEmpty() == true
            else -> false
        }
    }
}

package com.readfilestech.read_files_tech.data.files

import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** No free name left in a folder for an item: `name`, `name (1)` … `name (999)` are all taken. */
class NoFreeNameException(message: String) : IOException(message)

/**
 * Moving and copying items without ever replacing anything: the trash (in and out) and the explorer
 * (copy to, move to) share these, so a guard added here holds for both.
 */
object FileMoves {
    private const val MAX_CANDIDATES = 1_000

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
            copyThenDelete(from, to, isDir)
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
     * Copies the regular file [from] into [to]: a free path, or the reservation of [reserveUniquePath]
     * (truncated, never unlinked first). The copy is flushed to the device and its size checked before
     * this returns: a caller that deletes the source afterwards never deletes the only complete copy — a
     * full disk, a card pulled out, a source that shrank meanwhile all throw instead.
     */
    fun copyFile(from: File, to: File) {
        copyInto(from.toPath(), to.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
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
            val reserved = runCatching {
                if (isDir) Files.createDirectory(candidate.toPath()) else Files.createFile(candidate.toPath())
            }.isSuccess
            if (reserved) return candidate
        }
        throw NoFreeNameException("no free name for ${dir.path}/$name")
    }

    private fun candidates(dir: File, name: String): Sequence<File> = sequence {
        yield(File(dir, name))
        // `dot > 0`: a hidden file such as `.bashrc` has no extension, all of it is the name.
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1 until MAX_CANDIDATES) yield(File(dir, "$base ($i)$ext"))
    }

    private fun copyThenDelete(from: File, to: File, isDir: Boolean) {
        if (isDir) {
            copyTree(from.toPath(), to.toPath())
            FileTrees.delete(from)
        } else {
            copyFile(from, to)
            Files.delete(from.toPath())
        }
    }

    private fun copyInto(from: Path, to: Path, vararg modes: StandardOpenOption) {
        FileChannel.open(to, StandardOpenOption.WRITE, *modes).use { out ->
            Files.newInputStream(from).use { input -> input.copyTo(Channels.newOutputStream(out)) }
            out.force(true)
        }
        val copied = Files.size(to)
        val source = Files.size(from)
        if (copied != source) throw IOException("incomplete copy of $from: $copied of $source bytes")
    }

    /**
     * A recursive copy into a new tree, that never follows a symbolic link (no loop, no escape) and
     * never replaces a file. An entry it cannot copy — a link, a device — STOPS it: the source is
     * deleted after the copy, and the 2.15.x, which skipped such entries, lost them with it (GPT review
     * R3). The caller removes the partial copy.
     */
    private fun copyTree(from: Path, to: Path) {
        Files.walkFileTree(
            from,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) throw IOException("$file is not a regular file: the folder is left in place")
                    copyInto(file, to.resolve(from.relativize(file).toString()), StandardOpenOption.CREATE_NEW)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}

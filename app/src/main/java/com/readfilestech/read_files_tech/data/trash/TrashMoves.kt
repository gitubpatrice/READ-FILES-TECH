package com.readfilestech.read_files_tech.data.trash

import com.readfilestech.read_files_tech.data.files.FileTrees
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Moving items in and out of the trash, and reserving where they go back. */
internal object TrashMoves {
    private const val MAX_CANDIDATES = 1_000

    /**
     * Moves [from] to [to] — a free path, or the empty reservation of reserveUniquePath — never over
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
     * A move into the trash whose metadata could not be written: without it the entry could never be
     * found again, so the item goes back — under a free name if its place was taken meanwhile, never
     * over what took it (GPT and Claude reviews R3). If even that fails it stays in [itemDir], kept
     * rather than deleted, and the failure joins [cause].
     */
    fun putBack(dest: File, item: File, isDir: Boolean, itemDir: File, cause: IOException) {
        try {
            val back = reserveUniquePath(requireNotNull(item.parentFile), item.name, isDir)
            move(dest, back, isDir)
            FileTrees.delete(itemDir)
        } catch (failed: IOException) {
            cause.addSuppressed(failed)
        }
    }

    private fun copyThenDelete(from: File, to: File, isDir: Boolean) {
        if (isDir) {
            copyTree(from.toPath(), to.toPath())
            FileTrees.delete(from)
        } else {
            // Into the reservation itself (truncated, never unlinked first), or a new file.
            Files.newOutputStream(to.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                .use { out -> Files.copy(from.toPath(), out) }
            Files.delete(from.toPath())
        }
    }

    /**
     * A free path in [dir] for [name], CREATED to be reserved: `name`, then `name (1)`, `name (2)`… A
     * file is reserved by an exclusive file, a folder by an exclusive empty folder; the move then
     * replaces that reservation, never anything else.
     *
     * @throws TrashException no free name.
     */
    fun reserveUniquePath(dir: File, name: String, isDir: Boolean): File {
        for (candidate in candidates(dir, name)) {
            val reserved = runCatching {
                if (isDir) Files.createDirectory(candidate.toPath()) else Files.createFile(candidate.toPath())
            }.isSuccess
            if (reserved) return candidate
        }
        throw TrashException("no free name to restore ${dir.path}/$name")
    }

    private fun candidates(dir: File, name: String): Sequence<File> = sequence {
        yield(File(dir, name))
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1 until MAX_CANDIDATES) yield(File(dir, "$base ($i)$ext"))
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
                    Files.copy(file, to.resolve(from.relativize(file).toString()))
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}

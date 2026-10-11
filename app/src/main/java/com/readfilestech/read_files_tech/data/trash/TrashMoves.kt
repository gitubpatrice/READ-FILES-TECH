package com.readfilestech.read_files_tech.data.trash

import com.readfilestech.read_files_tech.data.files.FileTrees
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** Moving items in and out of the trash, and reserving where they go back. */
internal object TrashMoves {
    private const val MAX_CANDIDATES = 1_000

    /** A rename first (same volume, atomic); a copy then a deletion when it fails (another volume). */
    fun move(from: File, to: File, isDir: Boolean) {
        val renamed = runCatching { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }.isSuccess
        if (renamed) return
        if (isDir) {
            copyTree(from.toPath(), to.toPath())
            FileTrees.delete(from)
        } else {
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

    /** A recursive copy that never follows a symbolic link, nor copies one (no loop, no escape). */
    private fun copyTree(from: Path, to: Path) {
        Files.walkFileTree(
            from,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING)
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}

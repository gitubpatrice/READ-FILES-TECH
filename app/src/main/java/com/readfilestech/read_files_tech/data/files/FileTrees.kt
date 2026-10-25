package com.readfilestech.read_files_tech.data.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Walking and deleting a directory tree without ever leaving it. */
object FileTrees {
    /** Every regular file at or under [root], by path: a symbolic link is never followed, an unreadable entry skipped. */
    fun regularFiles(root: File): List<File> {
        val out = mutableListOf<File>()
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) out += file.toFile()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return out.sortedBy { it.path }
    }

    /**
     * Deletes [root] and everything under it, as far as it can: an entry that resists is left and the
     * walk goes on. A symbolic link is deleted as a link, never followed — Kotlin's
     * `File.deleteRecursively` walks with `isDirectory`, which FOLLOWS links, so a link planted in a
     * purged directory would have it empty the directory it points to.
     *
     * @return true when nothing is left at [root].
     */
    fun delete(root: File): Boolean {
        val path = root.toPath()
        // walkFileTree follows no link unless asked: a link is visited as a file and unlinked.
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.walkFileTree(path, Deleter)
        return !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }

    /**
     * Whether something is at [file], a dangling symbolic link included: `File.exists` follows the link
     * and says no, which would let a "do not overwrite" check be passed.
     */
    fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** A file, itself: not a directory, not a symbolic link (which `File.isFile` follows). */
    fun isRegularFile(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private object Deleter : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            file.toFile().delete()
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            dir.toFile().delete()
            return FileVisitResult.CONTINUE
        }
    }
}

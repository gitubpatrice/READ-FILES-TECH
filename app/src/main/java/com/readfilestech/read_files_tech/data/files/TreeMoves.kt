package com.readfilestech.read_files_tech.data.files

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * A folder moved to another volume (EXDEV), with the guarantees of a file (Claude review R5): copied
 * into a hidden `.rft-part-…` folder beside the target, published by ONE rename once whole, then the
 * source deleted file by file — each only if it is still the file that was copied — and its folders
 * only if empty. A killed copy leaves a hidden partial folder, never a plausible one under the real
 * name; a file that arrived or changed during the copy stays where it was.
 *
 * Reached by a trash in the private fallback (a path on no known volume) and by its restore.
 */
internal object TreeMoves {
    fun moveAcross(from: Path, to: Path) {
        val part = FileMoves.partBeside(to.toFile())
        val copied = HashMap<String, Identity>()
        try {
            copyTree(from, part, copied)
            FileMoves.move(part.toFile(), to.toFile(), isDir = true)
        } catch (e: IOException) {
            FileTrees.delete(part.toFile())
            throw e
        }
        deleteCopied(from, copied)
    }

    /**
     * A recursive copy into a new tree, that never follows a symbolic link (no loop, no escape) and
     * never replaces a file. An entry it cannot copy — a link, a device — STOPS it: the 2.15.x, which
     * skipped such entries, deleted them with the source (GPT review R3). Each file copied is recorded
     * in [copied] under its relative path, with its identity at the time of the copy.
     */
    private fun copyTree(from: Path, to: Path, copied: MutableMap<String, Identity>) {
        Files.walkFileTree(
            from,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) throw IOException("$file is not a regular file: the folder is left in place")
                    val relative = from.relativize(file).toString()
                    copied[relative] = Identity.of(attrs)
                    FileMoves.copyInto(file, to.resolve(relative), StandardOpenOption.CREATE_NEW)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /**
     * Deletes from [from] the files [copied] holds, still as they were copied, then the folders left
     * empty. Never throws: once the copy is published, a leftover at the source is a duplicate, not a
     * loss — and a failure here must never make a caller remove the published copy.
     */
    private fun deleteCopied(from: Path, copied: Map<String, Identity>) {
        runCatching {
            Files.walkFileTree(
                from,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (copied[from.relativize(file).toString()] == Identity.of(attrs)) runCatching { Files.delete(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        // Not recursive: a folder still holding a new or changed file stays.
                        dir.toFile().delete()
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }
}

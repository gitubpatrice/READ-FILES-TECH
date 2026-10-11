package com.readfilestech.read_files_tech.data.browse

import com.readfilestech.read_files_tech.data.files.FileMoves
import com.readfilestech.read_files_tech.data.files.FileTrees
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
import javax.inject.Inject

/** A name the user typed that cannot name a file (see [FileNames.isValid]). */
class InvalidFileNameException(name: String) : IOException("invalid file name: $name")

/** What a batch did: done, failed, and folders left out (copy and move take files only, as the 2.15.x). */
data class BatchResult(val done: Int, val failed: Int, val skippedFolders: Int = 0)

/**
 * The explorer's own changes to the user's files — `BatchOpsService` and the single-item actions of
 * `FileExplorerScreen` (2.15.x). Plain blocking I/O: callers run it off the main thread. Nothing here
 * ever replaces an existing item.
 */
class FileActions @Inject constructor() {
    private val random = SecureRandom()

    /** @throws InvalidFileNameException, FileAlreadyExistsException (the 2.15.x said nothing), IOException */
    fun createFolder(parent: File, name: String): File {
        if (!FileNames.isValid(name)) throw InvalidFileNameException(name)
        val folder = File(parent, name)
        Files.createDirectory(folder.toPath())
        return folder
    }

    /**
     * Renames [target] to [newName] in the same folder, never over another item.
     *
     * A change of CASE only (`photo.JPG` → `photo.jpg`) on a volume that ignores case — shared storage
     * does — finds "another" item that is the item itself: the 2.15.x refused it as already existing.
     * It goes through a temporary name instead.
     *
     * @throws InvalidFileNameException, FileAlreadyExistsException, IOException
     */
    fun rename(target: File, newName: String): File {
        val renamed = sibling(target, newName)
        if (newName == target.name) return target
        if (Files.exists(renamed.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            renameCase(target, renamed)
        } else {
            // No REPLACE_EXISTING: an item appearing meanwhile makes this throw, never disappear.
            Files.move(target.toPath(), renamed.toPath())
        }
        return renamed
    }

    /** [newName] next to [target]. @throws InvalidFileNameException, IOException (no parent folder) */
    private fun sibling(target: File, newName: String): File {
        if (!FileNames.isValid(newName)) throw InvalidFileNameException(newName)
        val parent = target.absoluteFile.parentFile ?: throw IOException("no parent folder: $target")
        return File(parent, newName)
    }

    /**
     * [renamed] exists: the same item under another case is renamed through a free temporary name, put
     * back if the second step fails; another item is never replaced.
     */
    private fun renameCase(target: File, renamed: File) {
        val from = target.toPath()
        val to = renamed.toPath()
        if (!Files.isSameFile(from, to)) throw FileAlreadyExistsException(renamed.path)
        val step = File(renamed.parentFile, ".rft-rename-${Integer.toUnsignedString(random.nextInt())}").toPath()
        Files.move(from, step)
        try {
            Files.move(step, to)
        } catch (e: IOException) {
            runCatching { Files.move(step, from) }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    /** Deletes [target] for good — a folder with all it holds, a link and not what it points to. */
    fun deleteForever(target: File): Boolean {
        FileTrees.delete(target)
        return Files.notExists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    fun deleteAll(targets: List<File>): BatchResult {
        val done = targets.count(::deleteForever)
        return BatchResult(done, targets.size - done)
    }

    /**
     * Copies or moves each FILE of [sources] into [destDir] under a free name (`report (1).pdf`): an item
     * of the same name there is never replaced — V-M1 of the 2.15.x, where moving onto a namesake lost
     * both. A move deletes its source only once the copy is complete and on the device (FileMoves); a
     * failed item leaves its source as it was and removes its partial copy. Moving a file into its own
     * folder changes nothing (the 2.15.x made a "(1)" copy and deleted the original).
     */
    fun transfer(sources: List<File>, destDir: File, move: Boolean): BatchResult {
        var done = 0
        var failed = 0
        var folders = 0
        for (source in sources) {
            when {
                Files.isDirectory(source.toPath()) -> folders++
                transferOne(source, destDir, move) -> done++
                else -> failed++
            }
        }
        return BatchResult(done, failed, folders)
    }

    private fun transferOne(source: File, destDir: File, move: Boolean): Boolean = when {
        !Files.isRegularFile(source.toPath()) -> false
        move && isSameFolder(source.absoluteFile.parentFile, destDir) -> true
        else -> try {
            transferReserved(source, FileMoves.reserveUniquePath(destDir, source.name, isDir = false), move)
            true
        } catch (expected: IOException) {
            false
        }
    }

    /** Into [dest], our reservation; on failure it goes — it held at most a partial copy, the source is whole. */
    private fun transferReserved(source: File, dest: File, move: Boolean) {
        try {
            if (move) FileMoves.move(source, dest, isDir = false) else FileMoves.copyFile(source, dest)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(dest.toPath()) }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
    }

    private fun isSameFolder(a: File?, b: File): Boolean =
        a != null && runCatching { Files.isSameFile(a.toPath(), b.toPath()) }.getOrDefault(false)
}

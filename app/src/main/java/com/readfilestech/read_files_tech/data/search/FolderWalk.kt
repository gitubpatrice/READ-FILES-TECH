package com.readfilestech.read_files_tech.data.search

import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * THE walk of a folder tree — the global search, the content search, the duplicate finder and the vault's
 * folder import: breadth
 * first from [root], over the regular files found. It never follows a link: a link to a folder is not
 * entered, a link to a file not read. A folder it cannot list is skipped, counted, and the walk goes on:
 * the content search and the duplicate finder of the 2.15.3 stopped at the first one (`Android/data` on
 * Android 11+, inventory D9); the global search already went on. Cancelled between two entries.
 *
 * @param skipHidden whether entries whose name starts with a dot are skipped below the root — a root the
 *   user chose inside a hidden folder is walked.
 * @param recursive false: the root's own files only, its folders not entered (the vault's folder import).
 * @param skipFolder folders left out whole ([isTrash], [isAppData]).
 */
class FolderWalk(
    private val root: File,
    private val skipHidden: Boolean,
    private val recursive: Boolean = true,
    private val skipFolder: (Path) -> Boolean = { false },
) {
    /** Folders that could not be listed so far, the root included. */
    var unreadableFolders = 0
        private set

    /**
     * Calls [onFile] with each regular file and its attributes, until it returns false. A root left out
     * ([skipFolder]) — itself, a folder inside it, or a link to it, read where it leads — is not walked: the
     * user choosing the trash or `Android/data` as the folder had it walked (GPT review R24).
     */
    suspend fun forEachFile(onFile: suspend (Path, BasicFileAttributes) -> Boolean) {
        val start = root.toPath()
        if (!walkable(start)) return
        val folders = ArrayDeque<Path>().apply { add(start) }
        while (folders.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (!list(folders.removeFirst(), folders, onFile)) return
        }
    }

    /** Whether [start] is walked: neither it nor where it leads is, or is in, a folder left out. */
    private fun walkable(start: Path): Boolean {
        val real = try {
            start.toRealPath()
        } catch (expected: IOException) {
            unreadableFolders++
            return false
        }
        return !leftOut(start) && !leftOut(real)
    }

    private fun leftOut(path: Path): Boolean = generateSequence(path) { it.parent }.any(skipFolder)

    /**
     * One folder's entries, each visited as it is listed — per entry, not per folder: a folder of thousands
     * of files kept a cancelled walk reading (Claude review R4 of the global search), and listed whole first,
     * a folder of a million names filled the memory before any was looked at (GPT review R24). False when
     * [onFile] stopped the walk. A folder that cannot be listed is skipped, counted.
     */
    private suspend fun list(dir: Path, folders: ArrayDeque<Path>, onFile: suspend (Path, BasicFileAttributes) -> Boolean): Boolean {
        val stream = open(dir) ?: return true
        stream.use {
            val entries = it.iterator()
            var going = true
            var entry = next(entries)
            while (going && entry != null) {
                going = visit(entry, folders, onFile)
                entry = next(entries)
            }
            return going
        }
    }

    /** One entry: a folder queued, a file handed to [onFile]; false when [onFile] stops the walk. */
    private suspend fun visit(child: Path, folders: ArrayDeque<Path>, onFile: suspend (Path, BasicFileAttributes) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val attributes = attributesOf(child)
        return when {
            attributes == null || (skipHidden && child.fileName?.toString().orEmpty().startsWith('.')) -> true
            attributes.isDirectory -> {
                if (recursive && !skipFolder(child)) folders.addLast(child)
                true
            }
            attributes.isRegularFile -> onFile(child, attributes)
            else -> true
        }
    }

    /** A folder's listing, or null — counted — when it cannot be opened. */
    private fun open(dir: Path): DirectoryStream<Path>? = try {
        Files.newDirectoryStream(dir)
    } catch (expected: IOException) {
        unreadableFolders++
        null
    } catch (expected: SecurityException) {
        unreadableFolders++
        null
    }

    /** The next entry, or null at the end — or at an error met while listing, the folder counted unreadable. */
    private fun next(entries: Iterator<Path>): Path? = try {
        if (entries.hasNext()) entries.next() else null
    } catch (expected: DirectoryIteratorException) {
        // Unchecked: an error met while listing, not when opening.
        unreadableFolders++
        null
    }

    private fun attributesOf(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (expected: IOException) {
        // Gone meanwhile: skipped.
        null
    }

    companion object {
        /**
         * The trash under both its names, the real one and a `.next` build's: its copies are not the user's
         * files — listed as duplicates of their originals by the 2.15.3, which tested names for a dot and
         * missed folders (inventory D8).
         */
        fun isTrash(dir: Path): Boolean {
            val name = dir.fileName?.toString()
            return name == SharedStorageNames.FLUTTER_TRASH_DIR ||
                name == SharedStorageNames.FLUTTER_TRASH_DIR + SharedStorageNames.NEXT_SUFFIX
        }

        /**
         * `Android/data` and `Android/obb`: the app's own folders on shared storage, and every other app's —
         * readable up to Android 10, where no tool should list them, let alone offer them for deletion.
         */
        fun isAppData(dir: Path): Boolean {
            val name = dir.fileName?.toString()
            return (name == "data" || name == "obb") && dir.parent?.fileName?.toString() == "Android"
        }
    }
}

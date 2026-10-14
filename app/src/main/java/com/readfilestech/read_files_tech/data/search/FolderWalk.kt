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
 * THE walk of a folder tree — the global search, the content search and the duplicate finder: breadth
 * first from [root], over the regular files found. It never follows a link: a link to a folder is not
 * entered, a link to a file not read. A folder it cannot list is skipped, counted, and the walk goes on:
 * the content search and the duplicate finder of the 2.15.3 stopped at the first one (`Android/data` on
 * Android 11+, inventory D9); the global search already went on. Cancelled between two entries.
 *
 * @param skipHidden whether entries whose name starts with a dot are skipped below the root — a root the
 *   user chose inside a hidden folder is walked.
 * @param skipFolder folders left out whole ([isTrash], [isAppData]).
 */
class FolderWalk(
    private val root: File,
    private val skipHidden: Boolean,
    private val skipFolder: (Path) -> Boolean = { false },
) {
    /** Folders that could not be listed so far, the root included. */
    var unreadableFolders = 0
        private set

    /** Calls [onFile] with each regular file and its attributes, until it returns false. */
    suspend fun forEachFile(onFile: suspend (Path, BasicFileAttributes) -> Boolean) {
        val folders = ArrayDeque<Path>().apply { add(root.toPath()) }
        while (folders.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            // Per entry, not per folder: a folder of thousands of files kept a cancelled walk reading
            // (Claude review R4 of the global search).
            if (!children(folders.removeFirst()).all { visit(it, folders, onFile) }) return
        }
    }

    /** One entry: a folder queued, a file handed to [onFile]; false when [onFile] stops the walk. */
    private suspend fun visit(child: Path, folders: ArrayDeque<Path>, onFile: suspend (Path, BasicFileAttributes) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val attributes = attributesOf(child)
        return when {
            attributes == null || (skipHidden && child.fileName?.toString().orEmpty().startsWith('.')) -> true
            attributes.isDirectory -> {
                if (!skipFolder(child)) folders.addLast(child)
                true
            }
            attributes.isRegularFile -> onFile(child, attributes)
            else -> true
        }
    }

    /** A folder's entries; one that cannot be read gives none, counted. */
    private fun children(dir: Path): List<Path> = try {
        Files.newDirectoryStream(dir).use(DirectoryStream<Path>::toList)
    } catch (expected: IOException) {
        unreadableFolders++
        emptyList()
    } catch (expected: SecurityException) {
        unreadableFolders++
        emptyList()
    } catch (expected: DirectoryIteratorException) {
        // An error met while listing, not when opening: unchecked, it went through the IOException above.
        unreadableFolders++
        emptyList()
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

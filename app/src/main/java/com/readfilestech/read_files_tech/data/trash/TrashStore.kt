package com.readfilestech.read_files_tech.data.trash

import com.readfilestech.read_files_tech.data.DartDateTime
import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom

/** What the trash refuses: its own folders, a target it cannot reserve, an id it cannot find a slot for. */
class TrashException(message: String) : IOException(message)

/**
 * The trash: a "soft" deletion moves the item into a hidden folder at the root of ITS volume
 * (`/storage/emulated/0/.RFT_Corbeille`, an SD card, or the app's own folder as a last resort), so a
 * rename does it at once whatever the size. Port of `TrashService` (`lib/services/trash_service.dart`,
 * 2.15.3), on the same folders and the same metadata: what the Flutter app put in the trash stays
 * restorable, and the other way round. Nothing is purged automatically.
 *
 * Better than the 2.15.x in two places it documented itself: an id and its folder are reserved by an
 * EXCLUSIVE creation (`createDirectory`), and so is the destination of a restored folder (the Dart
 * fell back to a test, "the window reduced to the strict minimum").
 *
 * @param names the shared-storage names, suffixed in a `.next` build (docs D-017).
 * @param fallbackBase where the trash goes when a path is on no known volume (`app_flutter/`).
 * @param volumeBases the volume roots to look in for existing trash folders.
 */
class TrashStore(
    private val names: SharedStorageNames,
    private val fallbackBase: File,
    private val volumeBases: () -> List<File>,
) {
    private val random = SecureRandom()

    /** Moves [item] into the trash of its volume. @throws IOException, the item then left in place. */
    fun moveToTrash(item: File): TrashEntry {
        checkTrashable(item)
        val isDir = Files.isDirectory(item.toPath(), LinkOption.NOFOLLOW_LINKS)
        val root = trashRootFor(item.path)
        val id = reserveId(root)
        val itemDir = File(File(root, TrashEntry.ITEMS_DIR), id)
        val size = if (isDir) -1L else item.length()
        val dest = File(itemDir, item.name)
        try {
            TrashMoves.move(item, dest, isDir)
        } catch (e: IOException) {
            FileTrees.delete(itemDir)
            throw e
        }
        val entry = TrashEntry(id, root, item.path, item.name, isDir, size, DartDateTime.now())
        try {
            AtomicFiles.writeBytes(entry.meta, entry.toJson().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            // Without its metadata the entry could never be found again: put the item back.
            TrashMoves.move(dest, item, isDir)
            FileTrees.delete(itemDir)
            throw e
        }
        return entry
    }

    /**
     * Every valid entry, the most recent first. A metadata file whose payload is gone is cleaned up — a
     * POSITIVE finding (docs D-019); one that cannot be read is left alone.
     */
    fun list(): List<TrashEntry> =
        existingTrashRoots(volumeBases() + fallbackBase, names.trashDir).flatMap(::entriesOf).sortedByDescending(TrashEntry::deletedAt)

    /** @throws TrashException the trash itself; @throws FileNotFoundException nothing there to move. */
    private fun checkTrashable(item: File) {
        if (isTrashRelated(item.path)) throw TrashException("this item cannot be put in the trash")
        if (!Files.exists(item.toPath(), LinkOption.NOFOLLOW_LINKS)) throw FileNotFoundException(item.path)
    }

    /** Puts [entry] back where it was; a name already taken gets ` (1)`, ` (2)`… Returns the final path. */
    fun restore(entry: TrashEntry): File {
        val parent = File(entry.originalPath).parentFile
        val plausible = TrashEntry.isPlausibleOriginalPath(entry.originalPath) && TrashEntry.isSafeSegment(entry.name) && parent != null
        if (!plausible) throw TrashException("invalid original path: ${entry.originalPath}")
        parent.mkdirs()
        // RESERVED, not tested: a file appearing between a test and the move would be replaced in
        // silence — and it was never in the trash.
        val dest = TrashMoves.reserveUniquePath(parent, entry.name, entry.isDir)
        TrashMoves.move(entry.payload, dest, entry.isDir)
        entry.meta.delete()
        FileTrees.delete(entry.itemDir)
        return dest
    }

    /** Deletes [entry] for good: payload and metadata. */
    fun deleteForever(entry: TrashEntry) {
        FileTrees.delete(entry.payload)
        entry.meta.delete()
        FileTrees.delete(entry.itemDir)
    }

    /** Empties the trash: (deleted, failed). */
    fun emptyAll(entries: List<TrashEntry>): Pair<Int, Int> {
        var failed = 0
        entries.forEach { entry ->
            deleteForever(entry)
            if (FileTrees.exists(entry.payload) || FileTrees.exists(entry.meta)) failed++
        }
        return (entries.size - failed) to failed
    }

    /**
     * The trash folder itself, one of its parents, or a volume root. Both the real name and this
     * build's are refused: a `.next` build must never move the user's real trash into its own.
     */
    fun isTrashRelated(path: String): Boolean {
        val normalized = path.trimEnd('/', '\\')
        val segments = normalized.split('/', '\\')
        val trashSegment = SharedStorageNames.FLUTTER_TRASH_DIR in segments || names.trashDir in segments
        return trashSegment || volumeRootOf(normalized) == normalized
    }

    private fun entriesOf(root: File): List<TrashEntry> {
        val metas = File(root, TrashEntry.META_DIR).listFiles().orEmpty()
            .filter { FileTrees.isRegularFile(it) && it.name.endsWith(".json") }
        return metas.mapNotNull { meta ->
            val entry = TrashEntry.read(meta, root)
            when {
                entry == null -> null
                // The file name must be the id it declares: a `meta/anything.json` dropped by another
                // app would otherwise surface as a legitimate entry.
                meta.name != "${entry.id}.json" -> null
                !FileTrees.exists(entry.payload) -> {
                    meta.delete()
                    FileTrees.delete(entry.itemDir)
                    null
                }
                else -> entry
            }
        }
    }

    private fun trashRootFor(path: String): File {
        val base = volumeRootOf(path)?.let(::File) ?: fallbackBase
        val root = File(base, names.trashDir)
        File(root, TrashEntry.ITEMS_DIR).mkdirs()
        File(root, TrashEntry.META_DIR).mkdirs()
        return root
    }

    /** An id and its folder at once: two deletions in the same millisecond must not share a folder. */
    private fun reserveId(root: File): String {
        val stamp = System.currentTimeMillis()
        repeat(ID_ATTEMPTS) {
            // DECIMAL, not hexadecimal: the id pattern accepts digits only (the 2.15.x learnt it the hard way).
            val id = "${stamp}_${Integer.toUnsignedLong(random.nextInt())}"
            try {
                Files.createDirectory(File(File(root, TrashEntry.ITEMS_DIR), id).toPath())
                return id
            } catch (expected: FileAlreadyExistsException) {
                // taken in the meantime: another one
            }
        }
        throw TrashException("no free slot in the trash")
    }

    companion object {
        private const val ID_ATTEMPTS = 64
        private const val EMULATED = "/storage/emulated/0"
        private const val SDCARD = "/sdcard"
        private val STORAGE_VOLUME = Regex("""^(/storage/[^/]+)""")

        /** The root of the volume holding [path], as `_volumeRootOf` of the 2.15.x, or null. */
        fun volumeRootOf(path: String): String? = when {
            path.startsWith(EMULATED) -> EMULATED
            path.startsWith(SDCARD) -> SDCARD
            else -> STORAGE_VOLUME.find(path)?.groupValues?.get(1)?.takeIf { it != "/storage/self" && it != "/storage/emulated" }
        }
    }
}

/** Every existing trash folder under [bases], once each (`/sdcard` is a link to `/storage/emulated/0`). */
private fun existingTrashRoots(bases: List<File>, trashDir: String): List<File> {
    val seen = mutableSetOf<String>()
    return bases.map { File(it, trashDir) }
        .filter { it.isDirectory }
        .filter { root -> seen.add(runCatching { root.toPath().toRealPath().toString() }.getOrDefault(root.path)) }
}

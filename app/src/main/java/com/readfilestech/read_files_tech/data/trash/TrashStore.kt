package com.readfilestech.read_files_tech.data.trash

import com.readfilestech.read_files_tech.data.DartDateTime
import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileMoves
import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import com.readfilestech.read_files_tech.data.files.StorageVisibility
import com.readfilestech.read_files_tech.data.files.isGone
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
 * @param privateRoots the app's own folders, under every name they have: no restore ever writes there.
 * @param visibility whether this app sees every file of shared storage: without it, other apps' files
 *   are hidden, not gone.
 * @param volumeBases the volume roots to look in for existing trash folders.
 */
class TrashStore(
    private val names: SharedStorageNames,
    private val fallbackBase: File,
    private val privateRoots: List<File>,
    private val visibility: StorageVisibility,
    private val volumeBases: () -> List<File>,
) {
    private val random = SecureRandom()

    /** Moves [target] into the trash of its volume. @throws IOException, the item then left in place. */
    fun moveToTrash(target: File): TrashEntry {
        // Absolute: the metadata keeps the path, and the reader refuses a relative one (GPT review R3).
        val item = target.absoluteFile
        checkTrashable(item)
        val isDir = Files.isDirectory(item.toPath(), LinkOption.NOFOLLOW_LINKS)
        val root = trashRootFor(item.path)
        val id = reserveId(root)
        val itemDir = File(File(root, TrashEntry.ITEMS_DIR), id)
        // -1 when the size cannot be read, as the 2.15.x (File.length() would say 0) (Claude review R3).
        val size = if (isDir) -1L else runCatching { Files.size(item.toPath()) }.getOrDefault(-1L)
        val dest = File(itemDir, item.name)
        try {
            FileMoves.move(item, dest, isDir)
        } catch (e: IOException) {
            FileTrees.delete(itemDir)
            throw e
        }
        val entry = TrashEntry(id, root, item.path, item.name, isDir, size, DartDateTime.now())
        try {
            AtomicFiles.writeBytes(entry.meta, entry.toJson().toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            TrashMoves.putBack(dest, item, isDir, itemDir, e)
            throw e
        }
        return entry
    }

    /** @throws TrashException the trash itself; @throws FileNotFoundException nothing there to move. */
    private fun checkTrashable(item: File) {
        if (isTrashRelated(item.path)) throw TrashException("this item cannot be put in the trash")
        if (!Files.exists(item.toPath(), LinkOption.NOFOLLOW_LINKS)) throw FileNotFoundException(item.path)
    }

    /**
     * Every valid entry, the most recent first. A metadata file whose payload is gone is cleaned up — a
     * POSITIVE finding (docs D-019); one that cannot be read, or whose payload is only unseen, is left alone.
     */
    fun list(): List<TrashEntry> =
        existingTrashRoots(volumeBases() + fallbackBase, names.trashDir).flatMap(::entriesOf).sortedByDescending(TrashEntry::deletedAt)

    /**
     * Puts [entry] back where it was; a name already taken gets ` (1)`, ` (2)`… Returns the final path.
     *
     * @throws TrashException a destination it must not write to (mayRestoreTo); the entry stays.
     */
    fun restore(entry: TrashEntry): File {
        val parent = File(entry.originalPath).parentFile
        val allowed = mayRestoreTo(entry, privateRoots) && !isTrashRelated(entry.originalPath)
        if (parent == null || !allowed) throw TrashException("invalid original path: ${entry.originalPath}")
        parent.mkdirs()
        // RESERVED, not tested: a file appearing between a test and the move would be replaced in
        // silence — and it was never in the trash.
        val dest = FileMoves.reserveUniquePath(parent, entry.name, entry.isDir)
        try {
            FileMoves.move(entry.payload, dest, entry.isDir)
        } catch (e: IOException) {
            // The reservation would be left where the item was: ours (created exclusively), and holding
            // at most a partial copy — the payload is still in the trash.
            FileTrees.delete(dest)
            throw e
        }
        entry.meta.delete()
        FileTrees.delete(entry.itemDir)
        return dest
    }

    /**
     * Deletes [entry] for good: payload, then metadata. The metadata goes only once the payload is
     * KNOWN gone — kept, the entry stays listed and can be tried again; the 2.15.x threw there (Claude
     * review R3).
     */
    fun deleteForever(entry: TrashEntry) {
        FileTrees.delete(entry.payload)
        if (!Files.notExists(entry.payload.toPath(), LinkOption.NOFOLLOW_LINKS)) return
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
                FileTrees.exists(entry.payload) -> entry
                // GONE, positively — not merely unseen: Files.exists is false as well when it cannot
                // tell, and a file of another app is hidden behind a revoked all-files access (D-019,
                // Claude review R3). Unseen: neither shown nor touched.
                visibility.isGone(entry.payload) -> {
                    meta.delete()
                    // Its folder only if empty: never what it may still hold under another name.
                    runCatching { Files.deleteIfExists(entry.itemDir.toPath()) }
                    null
                }
                else -> null
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

        /**
         * Where a restore may write (port plan, phase 3, finding 8, brought forward). A trash on SHARED
         * storage keeps its metadata in clear where another app can forge an entry whose `originalPath`
         * aims at the app's private folders — the vault's salt, the preferences, the editor's history —,
         * which the app alone can write. From such a trash: never under [privateRoots], and only on the
         * trash's own volume (`/sdcard` and `/storage/emulated/0` are one). The PRIVATE fallback trash
         * (`app_flutter/.RFT_Corbeille`) only the app writes: what the 2.15.x put there — its outputs kept
         * in `app_flutter/Files Tech/` when shared storage refused them — goes back where it was. Always:
         * under the name the entry carries, as the 2.15.x wrote it.
         */
        fun mayRestoreTo(entry: TrashEntry, privateRoots: List<File>): Boolean {
            val target = File(entry.originalPath)
            val plausible = TrashEntry.isPlausibleOriginalPath(entry.originalPath) && TrashEntry.isSafeSegment(entry.name) &&
                target.name == entry.name
            val trusted = isUnder(entry.trashRoot, privateRoots)
            val trashVolume = volumeRootOf(entry.trashRoot.path)?.let(::sameVolume)
            val onItsVolume = trashVolume == null || volumeRootOf(entry.originalPath)?.let(::sameVolume) == trashVolume
            return plausible && (trusted || (!isUnder(target, privateRoots) && onItsVolume))
        }

        private fun isUnder(file: File, roots: List<File>): Boolean {
            val path = file.toPath().normalize()
            return roots.any { path.startsWith(it.toPath().normalize()) }
        }

        /** `/sdcard` is a link to the primary volume: one volume under two names. */
        private fun sameVolume(root: String) = if (root == SDCARD) EMULATED else root
    }
}

/** Every existing trash folder under [bases], once each (`/sdcard` is a link to `/storage/emulated/0`). */
private fun existingTrashRoots(bases: List<File>, trashDir: String): List<File> {
    val seen = mutableSetOf<String>()
    return bases.map { File(it, trashDir) }
        .filter { it.isDirectory }
        .filter { root -> seen.add(runCatching { root.toPath().toRealPath().toString() }.getOrDefault(root.path)) }
}

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
import java.util.concurrent.ConcurrentHashMap

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
 * @param primary the current user's primary volume, the one `/sdcard` names: `/storage/emulated/0` for
 *   the owner, `/storage/emulated/10` in a work profile.
 */
class TrashStore(
    private val names: SharedStorageNames,
    private val fallbackBase: File,
    private val privateRoots: List<File>,
    private val visibility: StorageVisibility,
    private val volumeBases: () -> List<File>,
    private val primary: File,
) {
    private val random = SecureRandom()

    /** Entries being put in the trash: their metadata written, their item maybe not moved yet. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Moves [target] into the trash of its volume. @throws IOException, the item then left in place.
     *
     * The metadata FIRST, then the item (Claude review R4). A process killed in between — likely in a
     * batch of hundreds, the rename taking a millisecond and the synced metadata tens — leaves metadata
     * whose payload is gone, which [list] cleans, and the item still in its place. The other order, the
     * 2.15.x's, left the item in the trash with nothing to find it by.
     */
    fun moveToTrash(target: File): TrashEntry {
        // Absolute: the metadata keeps the path, and the reader refuses a relative one (GPT review R3).
        val item = target.absoluteFile
        checkTrashable(item)
        val isDir = Files.isDirectory(item.toPath(), LinkOption.NOFOLLOW_LINKS)
        val root = trashRootFor(item.path)
        val id = reserveId(root)
        // -1 when the size cannot be read, as the 2.15.x (File.length() would say 0) (Claude review R3).
        val size = if (isDir) -1L else runCatching { Files.size(item.toPath()) }.getOrDefault(-1L)
        val entry = TrashEntry(id, root, item.path, item.name, isDir, size, DartDateTime.now())
        // Listed meanwhile, the entry would look gone and lose its metadata: list() skips it.
        inFlight += id
        try {
            AtomicFiles.writeBytes(entry.meta, entry.toJson().toByteArray(Charsets.UTF_8))
            FileMoves.move(item, entry.payload, isDir)
        } catch (e: IOException) {
            // The item is where it was (FileMoves never deletes a source it has not fully copied).
            entry.meta.delete()
            FileTrees.delete(entry.itemDir)
            throw e
        } finally {
            inFlight -= id
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
        val allowed = mayRestoreTo(entry, privateRoots, primary.path) && !isTrashRelated(entry.originalPath)
        if (parent == null || !allowed) throw TrashException("invalid original path: ${entry.originalPath}")
        parent.mkdirs()
        // RESERVED, not tested: a file appearing between a test and the move would be replaced in
        // silence — and it was never in the trash.
        val dest = FileMoves.reserveUniquePath(parent, entry.name, entry.isDir)
        try {
            FileMoves.move(entry.payload, dest, entry.isDir)
        } catch (e: IOException) {
            // Our reservation, removed if still empty — never recursively: a failed move leaves no
            // partial copy there any more (FileMoves copies into a hidden part first), and a folder
            // that came to hold something is not ours (Claude review R5). The payload is still in the trash.
            FileMoves.discardReservation(dest)
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
                // Being written by moveToTrash: neither shown yet nor cleaned.
                entry.id in inFlight -> null
                FileTrees.exists(entry.payload) -> entry
                // GONE, positively — not merely unseen: Files.exists is false as well when it cannot
                // tell, and a file of another app is hidden behind a revoked all-files access (D-019,
                // Claude review R3). Unseen: neither shown nor touched.
                visibility.isGone(entry.payload) -> {
                    meta.delete()
                    // A copy killed half-way left its hidden part here (ours: the folder is reserved per
                    // id), which would hold its space for good (Claude review R5). Then the folder, only
                    // if empty: never what it may still hold under another name.
                    entry.itemDir.listFiles { file -> file.name.startsWith(PART_PREFIX) }.orEmpty().forEach(FileTrees::delete)
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
        private const val SDCARD = "/sdcard"
        private const val PART_PREFIX = FileMoves.PART_PREFIX
        private val STORAGE_VOLUME = Regex("""^(/storage/[^/]+)""")

        /** The shared storage of a user: 0 the owner, 10 a work profile, 150 Samsung's Secure Folder… */
        private val EMULATED_USER = Regex("""^(/storage/emulated/\d+)(?=/|$)""")

        /**
         * The root of the volume holding [path], or null — `_volumeRootOf` of the 2.15.x, which knew
         * user 0 only: in a work profile, Secure Folder or Android 15's private space, every item went to
         * the private fallback trash, a full COPY to internal storage (another volume), wiped with the
         * app's data (Claude reviews R4, R5). Each user's storage is its own volume now.
         */
        fun volumeRootOf(path: String): String? = when {
            path.startsWith(SDCARD) -> SDCARD
            else -> EMULATED_USER.find(path)?.groupValues?.get(1)
                ?: STORAGE_VOLUME.find(path)?.groupValues?.get(1)?.takeIf { it != "/storage/self" && it != "/storage/emulated" }
        }

        /**
         * Where a restore may write (port plan, phase 3, finding 8, brought forward). A trash on SHARED
         * storage keeps its metadata in clear where another app can forge an entry whose `originalPath`
         * aims at the app's private folders — the vault's salt, the preferences, the editor's history —,
         * which the app alone can write. From such a trash: never under [privateRoots], and only on the
         * trash's own volume (`/sdcard` and the user's [primary] volume are one). The PRIVATE fallback trash
         * (`app_flutter/.RFT_Corbeille`) only the app writes: what the 2.15.x put there — its outputs kept
         * in `app_flutter/Files Tech/` when shared storage refused them — goes back where it was. Always:
         * under the name the entry carries, as the 2.15.x wrote it.
         */
        fun mayRestoreTo(entry: TrashEntry, privateRoots: List<File>, primary: String): Boolean {
            val target = File(entry.originalPath)
            val plausible = TrashEntry.isPlausibleOriginalPath(entry.originalPath) && TrashEntry.isSafeSegment(entry.name) &&
                target.name == entry.name
            val trusted = isUnder(entry.trashRoot, privateRoots)
            fun volumeOf(path: String) = volumeRootOf(path)?.let { sameVolume(it, primary) }

            // `invariantSeparatorsPath`: `path` itself on Android; the JVM tests also run on Windows.
            val trashVolume = volumeOf(entry.trashRoot.invariantSeparatorsPath)
            val onItsVolume = trashVolume == null || volumeOf(entry.originalPath) == trashVolume
            return plausible && (trusted || (!isUnder(target, privateRoots) && onItsVolume))
        }

        private fun isUnder(file: File, roots: List<File>): Boolean {
            val path = file.toPath().normalize()
            return roots.any { path.startsWith(it.toPath().normalize()) }
        }

        /**
         * `/sdcard` is a link to the user's primary volume: one volume under two names. In a work profile
         * or Secure Folder that is `/storage/emulated/10`, not `/0`: the trash there, listed under its
         * `/sdcard` name, refused every restore (Claude review R6).
         */
        private fun sameVolume(root: String, primary: String) = if (root == SDCARD) primary else root
    }
}

/** Every existing trash folder under [bases], once each (`/sdcard` is a link to `/storage/emulated/0`). */
private fun existingTrashRoots(bases: List<File>, trashDir: String): List<File> {
    val seen = mutableSetOf<String>()
    return bases.map { File(it, trashDir) }
        .filter { it.isDirectory }
        .filter { root -> seen.add(runCatching { root.toPath().toRealPath().toString() }.getOrDefault(root.path)) }
}

package com.readfilestech.read_files_tech.data.viewer.zip

import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTrees
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * The entries of archives the user shares, one at a time: each extracted into a folder of its own in
 * `cache/zip_shared/`, the one private folder the FileProvider serves (`res/xml/file_paths.xml`), and
 * purged at every start (StartupCleanup) — another app may still be reading the last one shared.
 *
 * The 2.15.3 wrote each to the root of the cache under its name stripped to `[A-Za-z0-9._-]`: two
 * entries of one name in two folders overwrote each other, possibly while the app that received the
 * first one read it, and nothing purged them (inventory §5.2, D16).
 *
 * Never for the vault's files (docs D-028): the ZIP viewer offers no share of an entry when its file
 * may not leave the app.
 */
class ZipShares(
    /** `cache/zip_shared/`. */
    val folder: File,
) {
    private val random = SecureRandom()

    /**
     * [item] of [archive] as a file of its own name, bounded by [FileCaps.ZIP_ENTRY_DECOMPRESSED] and
     * checked as it is written; nothing is left if it fails.
     *
     * @throws ZipEntryException the entry cannot be read, or its data did not hold.
     */
    suspend fun extract(archive: ZipArchive, item: ZipItem): File {
        val name = requireNotNull(item.shareName) { "no name to share entry ${item.index} under" }
        Files.createDirectories(folder.toPath())
        val slot = File(folder, java.lang.Long.toUnsignedString(random.nextLong(), HEX))
        Files.createDirectory(slot.toPath())
        var done = false
        try {
            val target = File(slot, name)
            ZipFiles.write(archive, item, target, FileCaps.ZIP_ENTRY_DECOMPRESSED)
            done = true
            return target
        } finally {
            if (!done) FileTrees.delete(slot)
        }
    }

    /** Removes every entry shared before; true when none is left. */
    fun purge(): Boolean = FileTrees.delete(folder)

    companion object {
        /** The folder under `cacheDir`, named in `res/xml/file_paths.xml` too. */
        const val CACHE_DIR = "zip_shared"
        private const val HEX = 16

        /** The shared entries of the app whose cache folder is [cache]. */
        fun under(cache: File) = ZipShares(File(cache, CACHE_DIR))
    }
}

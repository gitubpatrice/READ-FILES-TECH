package com.readfilestech.read_files_tech.data.browse

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** What listing a folder gave. */
sealed interface Listing {
    data class Listed(val dir: File, val entries: List<FileEntry>) : Listing

    /** Gone, or a file: the explorer stays where it was. */
    data object NotADirectory : Listing

    /**
     * The folder exists but its content cannot be read — no storage permission, or an I/O error. NOT an
     * empty folder: the 2.15.x showed both alike, and an empty listing under a refused permission looked
     * like a folder with nothing in it.
     */
    data object Unreadable : Listing
}

/**
 * Lists a folder with `File.listFiles()`, as the 2.15.x did natively: Dart's `Directory.list()` lost
 * entries on Samsung (DefEx hid APKs under `/sdcard` even with all-files access); `listFiles` reads the
 * real directory.
 *
 * Each entry is read ONCE: its own attributes without following a link (is it a link?), then, for a
 * link only, its target's (what the explorer opens).
 */
object DirectoryListing {
    fun list(dir: File): Listing = if (dir.isDirectory) {
        dir.listFiles()?.let { children -> Listing.Listed(dir, children.map(::entryOf)) } ?: Listing.Unreadable
    } else {
        Listing.NotADirectory
    }

    /**
     * An entry that vanished between the listing and its attributes keeps its place with zero size and
     * date, as `File.length()`/`lastModified()` gave in the 2.15.x: hiding it would be a guess.
     */
    fun entryOf(file: File): FileEntry {
        val own = attributes(file, LinkOption.NOFOLLOW_LINKS)
        val isLink = own?.isSymbolicLink == true
        val target = if (isLink) attributes(file) else own
        val isDir = target?.isDirectory == true
        return FileEntry(
            path = file.absolutePath,
            name = file.name,
            isDirectory = isDir,
            size = if (isDir) 0L else target?.size() ?: 0L,
            modifiedMs = target?.lastModifiedTime()?.toMillis() ?: own?.lastModifiedTime()?.toMillis() ?: 0L,
            isSymlink = isLink,
        )
    }

    private fun attributes(file: File, vararg options: LinkOption): BasicFileAttributes? = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, *options)
    } catch (expected: IOException) {
        // Gone meanwhile, or a dangling link: the caller keeps the entry with what it has.
        null
    }
}

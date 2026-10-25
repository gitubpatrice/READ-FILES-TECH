package com.readfilestech.read_files_tech.data.browse

import java.io.File

/**
 * One entry of a folder, read once when the folder is listed: the explorer sorts, filters and draws
 * from these, and never asks the disk again for what it shows (the 2.15.x kept a `_statCache` for it).
 *
 * @param size the length of a file, 0 for a folder (as the 2.15.x `listDir`).
 * @param isSymlink the entry ITSELF is a link (read without following it). A folder reached through a
 *   link is listed as a folder; the flag lets the screens say so.
 */
data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedMs: Long,
    val isSymlink: Boolean,
) {
    val file: File get() = File(path)

    /** Lower case, without the dot; empty for a folder (see [FileNames.extension]). */
    val extension: String get() = if (isDirectory) "" else FileNames.extension(name)

    val isHidden: Boolean get() = name.startsWith('.')
}

/** Name rules shared by every screen that names, renames or recognises a file. */
object FileNames {
    /**
     * `PathUtils.fileExt` of files_tech_core: lower case, without the dot. Empty when there is none, when
     * the dot comes first (a hidden `.bashrc`) or last (`foo.`); `archive.tar.gz` gives `gz`.
     */
    fun extension(name: String): String {
        val base = name.substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        if (dot <= 0 || dot == base.length - 1) return ""
        return base.substring(dot + 1).lowercase()
    }

    /**
     * A name the user may give to a new or renamed item: `isValidFileName` of the 2.15.x — no separator,
     * not `.` nor `..`, no control character — plus not empty, which its callers checked apart.
     */
    fun isValid(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it < ' ' }
}

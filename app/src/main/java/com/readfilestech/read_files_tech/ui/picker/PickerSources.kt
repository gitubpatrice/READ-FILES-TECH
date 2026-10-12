package com.readfilestech.read_files_tech.ui.picker

import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.recents.RecentFiles
import com.readfilestech.read_files_tech.data.storage.CommonFolders
import com.readfilestech.read_files_tech.data.storage.VolumeRoot
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** The folders of the "Browse" tab, which the screen names and draws. */
enum class ShortcutKind { DOWNLOADS, DOCUMENTS, CAMERA, PICTURES, MOVIES, MUSIC, SCREENSHOTS, WHATSAPP, FILES_TECH, APKS, VOLUME }

/**
 * A shortcut: a folder, with its own filter for APKs, its label for another volume. [FILES_TECH][ShortcutKind]
 * is created when opened, as the 2.15.x did.
 */
data class PickerShortcut(val kind: ShortcutKind, val dir: File, val extensions: Set<String>? = null, val label: String? = null)

/**
 * What the picker offers (`RftPickerScreen` of the 2.15.3): the recent files and what the tools made,
 * the shortcuts, the folders of the primary volume.
 *
 * @param primary the user's primary volume.
 * @param otherVolumes the volumes besides it (an SD card): the 2.15.x reached them only by SAF.
 */
class PickerSources(
    private val recents: RecentFiles,
    private val outputs: OutputStorage,
    private val primary: () -> File,
    private val otherVolumes: () -> List<VolumeRoot>,
) {
    /**
     * The recent files, then what lies in the output folders — every fallback included (inventory D46)
     * — less what the user removed, the most recent first, filtered by [extensions].
     */
    fun recents(extensions: Set<String>?): List<FileEntry> {
        val dismissed = recents.dismissed()
        val opened = recents.load().filter { it.path !in dismissed }.map {
            FileEntry(
                it.path,
                it.name,
                isDirectory = false,
                size = it.sizeBytes,
                modifiedMs = it.lastOpened.epochMicros() / MICROS_PER_MILLI,
                isSymlink = false
            )
        }
        val known = opened.mapTo(HashSet(), FileEntry::path)
        val made = outputs.bases().flatMap { base -> OutputCategory.entries.flatMap { outputsIn(File(base, it.folderName)) } }
            .filter { it.path !in dismissed && it.path !in known }
            .distinctBy(FileEntry::path)
        return (opened + made).sortedByDescending(FileEntry::modifiedMs).filter {
            extensions == null || it.extension in extensions
        }
    }

    /**
     * Removed from the picker until opened again: ONLY this entry — the 2.15.x saved its whole merged,
     * filtered list over the recents, losing favourites of other types (inventory D10, D45).
     */
    fun remove(path: String) = recents.dismiss(path)

    /**
     * The shortcuts: those of the 2.15.x relative to the user's primary volume, a folder that is not
     * there left out (its WhatsApp tile opened onto an error, F33), screenshots where this phone keeps
     * them (F30), "Files Tech" where the outputs really go (F33), APKs only when the filter takes them
     * (D11), then the other volumes. The filter never hides a folder (F32).
     */
    fun shortcuts(extensions: Set<String>?, folderMode: Boolean): List<PickerShortcut> {
        val root = primary()
        val folders = listOfNotNull(
            PickerShortcut(ShortcutKind.DOWNLOADS, File(root, "Download")),
            PickerShortcut(ShortcutKind.DOCUMENTS, File(root, "Documents")),
            PickerShortcut(ShortcutKind.CAMERA, File(root, "DCIM/Camera")),
            PickerShortcut(ShortcutKind.PICTURES, File(root, "Pictures")),
            PickerShortcut(ShortcutKind.MOVIES, File(root, "Movies")),
            PickerShortcut(ShortcutKind.MUSIC, File(root, "Music")),
            CommonFolders.screenshots(root)?.let { PickerShortcut(ShortcutKind.SCREENSHOTS, it) },
            PickerShortcut(ShortcutKind.WHATSAPP, File(root, WHATSAPP_DOCUMENTS)),
        ).filter { it.dir.isDirectory }
        val apks = if (!folderMode && (extensions == null || APK in extensions)) {
            listOf(PickerShortcut(ShortcutKind.APKS, File(root, "Download"), extensions = setOf(APK)))
        } else {
            emptyList()
        }
        val volumes = otherVolumes().map { PickerShortcut(ShortcutKind.VOLUME, it.dir, label = it.label) }
        return folders + PickerShortcut(ShortcutKind.FILES_TECH, outputs.basePath()) + apks + volumes
    }

    /** The other folders at the top of the primary volume: not hidden, not `Android`, not a shortcut. */
    fun folders(shortcuts: List<PickerShortcut>): List<File> {
        val taken = shortcuts.mapTo(HashSet()) { it.dir.absolutePath }
        return primary().listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith('.') && it.name != "Android" && it.absolutePath !in taken }
            .sortedBy { it.name.lowercase() }
    }

    /** The files a tool left in [dir]: regular files only — a link is never followed — and not hidden. */
    private fun outputsIn(dir: File): List<FileEntry> = dir.listFiles().orEmpty()
        .filter { !it.name.startsWith('.') && Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        .map {
            FileEntry(it.absolutePath, it.name, isDirectory = false, size = it.length(), modifiedMs = it.lastModified(), isSymlink = false)
        }

    private companion object {
        const val APK = "apk"
        const val MICROS_PER_MILLI = 1_000L
        const val WHATSAPP_DOCUMENTS = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"
    }
}

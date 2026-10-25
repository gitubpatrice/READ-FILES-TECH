package com.readfilestech.read_files_tech.ui.explorer

import com.readfilestech.read_files_tech.data.browse.EntryOrder
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.browse.Listing
import com.readfilestech.read_files_tech.data.browse.SortMode
import com.readfilestech.read_files_tech.data.storage.VolumeRoot
import java.io.File

/** The apps a file's menu sends to by name, offered only when installed: an entry that always failed is not one. */
data class InstalledApps(val kDrive: Boolean = false, val proton: Boolean = false, val pdfTech: Boolean = false)

/** What the explorer chooses for another screen (`pickMode` of the 2.15.x, which knew one file only). */
enum class PickMode {
    FOLDER,
    FILE,
    FILES,
    ;

    /** The route argument. */
    val argument: String get() = name.lowercase()

    companion object {
        fun of(argument: String?): PickMode? = entries.find { it.argument == argument }
    }
}

/** Why a folder shows nothing. */
enum class FolderProblem {
    /** No storage access, under shared storage: the banner and the "settings" button. */
    ACCESS_DENIED,

    /** Access granted, but this folder cannot be listed (`/storage/emulated`, a pulled card). */
    UNREADABLE,
}

data class ExplorerUiState(
    val dir: File? = null,
    val loading: Boolean = true,
    val problem: FolderProblem? = null,
    /** No storage access under shared storage: what is listed may be incomplete. */
    val accessBanner: Boolean = false,
    /** Every entry of the folder, sorted; [visible] is what the filters keep of it. */
    val entries: List<FileEntry> = emptyList(),
    val visible: List<FileEntry> = emptyList(),
    val query: String = "",
    val showHidden: Boolean = false,
    val sort: SortMode = SortMode.NAME,
    val selection: Set<String> = emptySet(),
    val canGoBack: Boolean = false,
    /**
     * What the explorer chooses for another screen, if anything: no menu, no selection, no action. The files
     * chosen in [PickMode.FILES] are not here but in [PickedFiles]: what a change of folder sets, it cannot touch.
     */
    val pick: PickMode? = null,
    /** Only files of these extensions are shown, folders always (a quick access, a picker's filter). */
    val extensions: Set<String>? = null,
    /** The caller's name for this place ("Videos"), shown instead of the explorer's. */
    val title: String? = null,
    val volumes: List<VolumeRoot> = emptyList(),
    val installed: InstalledApps = InstalledApps(),
) {
    val selecting: Boolean get() = selection.isNotEmpty()

    /** Choosing a destination folder (copy / move to): files are shown, not actionable. */
    val pickFolder: Boolean get() = pick == PickMode.FOLDER

    /** Entries the filters hide that are files of other extensions: the empty state says why. */
    val filteredOut: Boolean get() = extensions != null && entries.any { !it.isDirectory && it.extension !in extensions }

    /** The selected entries — ALL of them, a filter typed since included: what the bar counts is what is acted on (Claude R5). */
    val selectedEntries: List<FileEntry> get() = entries.filter { it.path in selection }
}

// The state's changes, pure: the screen's logic is tested on these, without Android.

/**
 * A folder just listed. [denied]: no storage access under shared storage — on Android 11+ such a
 * listing is INCOMPLETE (other apps' files are hidden), not empty, so the banner shows whatever it
 * holds; the 2.15.x showed it on an empty listing only. A selection keeps what is still there.
 */
fun ExplorerUiState.listed(dir: File, listing: Listing, denied: Boolean, mounted: List<VolumeRoot>, canGoBack: Boolean): ExplorerUiState {
    val (entries, problem) = when (listing) {
        // Gone meanwhile (deleted, card pulled out): stay where we were.
        Listing.NotADirectory -> return copy(loading = false, canGoBack = canGoBack)
        Listing.Unreadable -> emptyList<FileEntry>() to if (denied) FolderProblem.ACCESS_DENIED else FolderProblem.UNREADABLE
        is Listing.Listed -> listing.entries to if (listing.entries.isEmpty() && denied) FolderProblem.ACCESS_DENIED else null
    }
    val sorted = EntryOrder.sort(entries, sort)
    val present = sorted.mapTo(HashSet(), FileEntry::path)
    return copy(
        dir = dir,
        loading = false,
        problem = problem,
        accessBanner = denied,
        entries = sorted,
        selection = selection.filterTo(mutableSetOf()) { it in present },
        canGoBack = canGoBack,
        volumes = mounted,
    ).refiltered()
}

fun ExplorerUiState.refiltered(): ExplorerUiState = copy(visible = EntryOrder.filter(entries, showHidden, query, extensions))

fun ExplorerUiState.sortedBy(mode: SortMode): ExplorerUiState = copy(sort = mode, entries = EntryOrder.sort(entries, mode)).refiltered()

fun ExplorerUiState.toggled(entry: FileEntry): ExplorerUiState =
    copy(selection = if (entry.path in selection) selection - entry.path else selection + entry.path)

/** "Select all": what is SHOWN, as the 2.15.x — not the hidden or filtered-out entries. */
fun ExplorerUiState.allSelected(): ExplorerUiState = copy(selection = visible.mapTo(mutableSetOf(), FileEntry::path))

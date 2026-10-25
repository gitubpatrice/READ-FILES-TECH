package com.readfilestech.read_files_tech.data.viewer.image

import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.EntryOrder
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.browse.Listing
import com.readfilestech.read_files_tech.data.browse.SortMode
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import java.io.File

/**
 * How the explorer showed the folder an image was opened from — its sort, its hidden files, its name
 * filter, its extension filter — so that the gallery lists the image's siblings as the user saw them.
 * It travels in the viewer's route: a few characters where the list itself could be thousands of paths.
 */
data class GalleryOrder(val sort: SortMode, val showHidden: Boolean, val query: String = "", val extensions: Set<String>? = null) {
    /** `NAME:0:*:query` — the query last, it may hold anything, the separator included. */
    fun toArgument(): String =
        listOf(sort.name, if (showHidden) SHOWN else HIDDEN, extensions?.joinToString(EXTENSION_SEPARATOR) ?: ANY, query)
            .joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = ":"
        private const val EXTENSION_SEPARATOR = ","
        private const val ANY = "*"
        private const val SHOWN = "1"
        private const val HIDDEN = "0"
        private const val PARTS = 4

        /** The order an argument names; null for none, or one [toArgument] did not write (an old or forged route). */
        fun fromArgument(argument: String?): GalleryOrder? {
            val parts = argument?.split(SEPARATOR, limit = PARTS)?.takeIf { it.size == PARTS } ?: return null
            val (sortName, hidden, extensionList) = parts
            val sort = SortMode.entries.find { it.name == sortName }
            val showHidden = when (hidden) {
                SHOWN -> true
                HIDDEN -> false
                else -> null
            }
            val extensions = extensionList.takeUnless { it == ANY }?.split(EXTENSION_SEPARATOR)?.filter(String::isNotEmpty)?.toSet()
            return if (sort == null || showHidden == null) null else GalleryOrder(sort, showHidden, query = parts.last(), extensions)
        }
    }
}

/** One image of the viewer: what it reads, and its entry for the info dialog — none for the vault's (D-028). */
data class ImagePage(val source: ViewerSource, val entry: FileEntry?)

/** The images the viewer swipes through, and the one it opens on. */
data class Gallery(val pages: List<ImagePage>, val start: Int)

/**
 * The viewer's images (`ImageViewerScreen.siblings` of the 2.15.3): the image opened from the explorer
 * among the images of its folder, in the explorer's order (`FES:394-401`); opened from anywhere else —
 * the home, the picker, a tool's result — alone, as the 2.15.3.
 */
object ImageGallery {
    /**
     * @param order how the explorer showed the folder; null when the image was not opened from it.
     * @param extensions what the image viewer opens: the siblings it may show.
     */
    fun load(source: ViewerSource, order: GalleryOrder?, extensions: Set<String>): Gallery {
        // A file of the storage: the vault's sources have none, nor siblings to show.
        val file = source.shareable
        val siblings = if (order != null && file != null) siblingsOf(file, order, extensions) else emptyList()
        val start = siblings.indexOfFirst { it.path == file?.absolutePath }
        return if (start < 0) {
            // Not opened from the explorer, or not among the images it shows any more (gone, renamed, hidden
            // since): alone — the 2.15.3 opened the folder's FIRST image (F18).
            Gallery(listOf(ImagePage(source, file?.let(DirectoryListing::entryOf))), 0)
        } else {
            Gallery(siblings.mapIndexed { i, entry -> ImagePage(if (i == start) source else StorageFileSource(entry.file), entry) }, start)
        }
    }

    /** The images the explorer shows in the folder of [file], in its two steps: sorted when listed, then filtered (ExplorerState). */
    private fun siblingsOf(file: File, order: GalleryOrder, extensions: Set<String>): List<FileEntry> {
        val listing = file.parentFile?.let(DirectoryListing::list) as? Listing.Listed ?: return emptyList()
        return EntryOrder.filter(EntryOrder.sort(listing.entries, order.sort), order.showHidden, order.query, order.extensions)
            .filter { !it.isDirectory && it.extension in extensions }
    }
}

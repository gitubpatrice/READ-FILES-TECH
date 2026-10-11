package com.readfilestech.read_files_tech.data.browse

/** The explorer's sort, as `SortService` of the 2.15.x: folders first, then by the chosen key. */
enum class SortMode { NAME, DATE, SIZE }

/** Sorting and filtering a listing — pure, so the screen recomputes them on its own data only. */
object EntryOrder {
    /** Name: case-insensitive, A to Z. Date and size: the most recent, the largest first. */
    fun sort(entries: List<FileEntry>, mode: SortMode): List<FileEntry> {
        val byKey: Comparator<FileEntry> = when (mode) {
            // Lower-cased once per entry, not once per comparison (the 2.15.x's "Schwartzian transform").
            SortMode.NAME -> {
                val keys = entries.associateWith { it.name.lowercase() }
                compareBy { keys.getValue(it) }
            }
            SortMode.DATE -> compareByDescending { it.modifiedMs }
            SortMode.SIZE -> compareByDescending { it.size }
        }
        return entries.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.then(byKey))
    }

    /**
     * What the explorer shows: hidden entries only on request, files outside [extensions] removed (when
     * given; folders always stay, to browse through), and names containing [query], case ignored.
     */
    fun filter(entries: List<FileEntry>, showHidden: Boolean, query: String, extensions: Set<String>? = null): List<FileEntry> {
        val needle = query.lowercase()
        return entries.filter { entry ->
            (showHidden || !entry.isHidden) &&
                (extensions == null || entry.isDirectory || entry.extension in extensions) &&
                (needle.isEmpty() || needle in entry.name.lowercase())
        }
    }
}

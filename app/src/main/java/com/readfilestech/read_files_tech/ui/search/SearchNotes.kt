package com.readfilestech.read_files_tech.ui.search

import androidx.annotation.PluralsRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.MIB

/**
 * What a finished search did not search, said under its results — the content search's notes, which the global
 * search now shares: stopped at [FileCaps.SEARCH_RESULTS], [tooLarge] files over [FileCaps.SEARCH_CONTENT] (said by
 * [tooLargeText]), [unreadable] files, [unreadableFolders].
 */
@Composable
internal fun SearchNotes(full: Boolean, tooLarge: Int, @PluralsRes tooLargeText: Int, unreadable: Int, unreadableFolders: Int) {
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.error
    if (full) {
        val found = FileCaps.SEARCH_RESULTS
        Text(pluralStringResource(R.plurals.content_search_full, found, found), style = style, color = color)
    }
    if (tooLarge > 0) {
        val megabytes = (FileCaps.SEARCH_CONTENT / MIB).toInt()
        Text(pluralStringResource(tooLargeText, tooLarge, tooLarge, megabytes), style = style, color = color)
    }
    if (unreadable > 0) {
        Text(pluralStringResource(R.plurals.content_search_unreadable, unreadable, unreadable), style = style, color = color)
    }
    if (unreadableFolders > 0) {
        Text(pluralStringResource(R.plurals.walk_unreadable_folders, unreadableFolders, unreadableFolders), style = style, color = color)
    }
}

package com.readfilestech.read_files_tech.ui.common

import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.BatchResult

/** How a batch is told: "3 copied · 1 error · 2 folders left out" — pure, so its wording is tested. */
object BatchMessages {
    /** [done] is the plural of what succeeded ("%d copied"). A batch that partly failed is an error (2.15.x). */
    fun of(result: BatchResult, done: Int): UserMessage {
        var text = withFailures(UiText.Plural(done, result.done), result.failed)
        if (result.skippedFolders > 0) {
            text = UiText.Joined(text, UiText.Plural(R.plurals.folders_skipped, result.skippedFolders), R.string.joined_dot)
        }
        return UserMessage(text, isError = result.failed > 0)
    }

    fun withFailures(text: UiText, failed: Int): UiText =
        if (failed == 0) text else UiText.Joined(text, UiText.Plural(R.plurals.errors_count, failed), R.string.joined_dot)
}

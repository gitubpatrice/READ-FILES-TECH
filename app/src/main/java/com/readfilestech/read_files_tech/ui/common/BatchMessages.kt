package com.readfilestech.read_files_tech.ui.common

import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.BatchResult

/** How a batch is told: "3 copied · 1 error · 2 folders left out" — pure, so its wording is tested. */
object BatchMessages {
    /** [done] is the plural of what succeeded ("%d copied"). A batch that partly failed is an error (2.15.x). */
    fun of(result: BatchResult, done: Int): UserMessage {
        var text = withFailures(UiText.Plural(done, result.done), result.failed, result.errors.map(IoErrors::reasonOf))
        if (result.skippedFolders > 0) {
            text = UiText.Joined(text, UiText.Plural(R.plurals.folders_skipped, result.skippedFolders), R.string.joined_dot)
        }
        return UserMessage(text, isError = result.failed > 0)
    }

    /** [text], then the count of the [failed] items and the cause they share ([withReason]), if any failed. */
    fun withFailures(text: UiText, failed: Int, reasons: List<UiText> = emptyList()): UiText =
        if (failed == 0) {
            text
        } else {
            UiText.Joined(text, withReason(UiText.Plural(R.plurals.errors_count, failed), failed, reasons), R.string.joined_dot)
        }

    /**
     * [text], followed by the cause of its [failed] items when each said one and all said the same, and it tells the
     * user something: "1 error: not enough space". A vague cause, or several, adds nothing (2.15.x said none).
     */
    fun withReason(text: UiText, failed: Int, reasons: List<UiText>): UiText {
        val shared = reasons.distinct().singleOrNull()?.takeIf { reasons.size == failed && it !in VAGUE }
        return if (shared == null) text else UiText.Joined(text, shared, R.string.joined_reason)
    }

    private val VAGUE = setOf(uiText(R.string.error_io), uiText(R.string.error_unexpected))
}

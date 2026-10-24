package com.readfilestech.read_files_tech.ui.vault

import androidx.annotation.PluralsRes
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.vault.VaultBackups

/**
 * How an import or a restore ended, in one line: "3 encrypted · 1 kept · 1 error". Pure, so its wording is
 * tested. Null when there is nothing to say.
 */
internal object VaultMessages {
    /**
     * [stoppedByLock]: the vault locked during the import, which stopped there — said, not counted as errors.
     * [keptLabel] says why a file was kept: a namesake the user chose to keep, or one already there.
     * [notDeleted]: originals to delete that were left in place — changed since read, or refused.
     */
    @Suppress("LongParameterList") // the counts of one import, each said apart
    fun imported(
        done: Int,
        kept: Int,
        failed: Int,
        stoppedByLock: Boolean,
        deleted: Int = 0,
        @PluralsRes keptLabel: Int = R.plurals.vault_kept,
        notDeleted: Int = 0,
    ): UiText? = joined(
        buildList {
            if (done > 0) add(UiText.Plural(R.plurals.vault_imported, done))
            if (deleted > 0) add(UiText.Plural(R.plurals.vault_originals_deleted, deleted))
            if (notDeleted > 0) add(UiText.Plural(R.plurals.vault_originals_not_deleted, notDeleted))
            if (kept > 0) add(UiText.Plural(keptLabel, kept))
            if (failed > 0) add(UiText.Plural(R.plurals.errors_count, failed))
            if (stoppedByLock) add(uiText(R.string.vault_reason_locked))
        },
    )

    /**
     * Each outcome of a restore apart: the 2.15.x said "N skipped (namesake)" when the writes had FAILED, and
     * the user concluded the files were there. A failure points at the free space.
     */
    fun restored(result: VaultBackups.RestoreResult): UiText {
        val parts = buildList {
            add(UiText.Plural(R.plurals.vault_restored, result.restored))
            if (result.skipped > 0) add(UiText.Plural(R.plurals.vault_already_there, result.skipped))
            if (result.refused.isNotEmpty()) add(UiText.Plural(R.plurals.vault_left_out, result.refused.size))
            if (result.failed.isNotEmpty()) add(UiText.Plural(R.plurals.errors_count, result.failed.size))
        }
        val line = requireNotNull(joined(parts))
        return if (result.failed.isEmpty()) line else uiText(R.string.vault_restore_check_space, line)
    }

    private fun joined(parts: List<UiText>): UiText? = parts.reduceOrNull { line, part -> UiText.Joined(line, part, R.string.joined_dot) }
}

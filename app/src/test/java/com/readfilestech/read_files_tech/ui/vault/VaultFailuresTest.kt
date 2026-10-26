package com.readfilestech.read_files_tech.ui.vault

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.uiText
import org.junit.jupiter.api.Test

/** The words of a lockout: its wait rounded up, and the unlock form's countdown, gone once over. */
class VaultFailuresTest {
    @Test
    fun `an import that failed for one cause says it, as the explorer's batches`() {
        val space = uiText(R.string.error_no_space)
        val summary = VaultMessages.imported(1, 0, failed = 2, stoppedByLock = false, reasons = listOf(space, space))
        assertThat(summary).isEqualTo(
            UiText.Joined(
                UiText.Plural(R.plurals.vault_imported, 1),
                UiText.Joined(UiText.Plural(R.plurals.errors_count, 2), space, R.string.joined_reason),
                R.string.joined_dot,
            ),
        )
    }

    @Test
    fun `a wait is whole seconds under a minute, whole minutes above, rounded up, never zero`() {
        assertThat(waitText(1)).isEqualTo(UiText.Plural(R.plurals.vault_wait_seconds, 1))
        assertThat(waitText(0)).isEqualTo(UiText.Plural(R.plurals.vault_wait_seconds, 1))
        assertThat(waitText(59_001)).isEqualTo(UiText.Plural(R.plurals.vault_wait_minutes, 1))
        assertThat(waitText(60_000)).isEqualTo(UiText.Plural(R.plurals.vault_wait_minutes, 1))
        assertThat(waitText(60_001)).isEqualTo(UiText.Plural(R.plurals.vault_wait_minutes, 2))
        assertThat(waitText(1_668_000)).isEqualTo(UiText.Plural(R.plurals.vault_wait_minutes, 28))
    }

    @Test
    fun `the countdown says what is left of the lockout, and nothing once it is over`() {
        assertThat(lockoutText(endsAt = 100_000, now = 40_000))
            .isEqualTo(uiText(R.string.vault_lockout, UiText.Plural(R.plurals.vault_wait_minutes, 1)))
        assertThat(lockoutText(endsAt = 100_000, now = 99_500))
            .isEqualTo(uiText(R.string.vault_lockout, UiText.Plural(R.plurals.vault_wait_seconds, 1)))
        assertThat(lockoutText(endsAt = 100_000, now = 100_000)).isNull()
        assertThat(lockoutText(endsAt = 100_000, now = 160_000)).isNull()
    }
}

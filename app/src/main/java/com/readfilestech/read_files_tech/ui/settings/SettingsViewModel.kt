package com.readfilestech.read_files_tech.ui.settings

import androidx.lifecycle.ViewModel
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.panic.PanicService
import com.readfilestech.read_files_tech.ui.common.ApplicationScope
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    /** "Erase everything?" on screen. */
    val confirmPanic: Boolean = false,
    /** The panic under way: nothing else is offered meanwhile. */
    val erasing: Boolean = false,
)

/**
 * `SettingsScreen` of the 2.15.3, its panic section for now; the output folder and the automatic share
 * follow with the screens of phase 8.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val panic: PanicService,
    private val messages: UserMessages,
    @param:ApplicationScope private val scope: CoroutineScope,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()

    fun askPanic() = mutableState.update { if (it.erasing) it else it.copy(confirmPanic = true) }

    fun cancelPanic() = mutableState.update { it.copy(confirmPanic = false) }

    /**
     * Erases everything, on the application's scope: leaving the screen — the very reflex of a user in
     * danger — must not stop an erasure half-way. How it ended is said wherever the user is by then.
     */
    fun panic() {
        if (mutableState.value.erasing) return
        mutableState.update { it.copy(confirmPanic = false, erasing = true) }
        scope.launch {
            try {
                val report = panic.wipeAll()
                val left = notErased(report)
                if (left == null) {
                    messages.info(uiText(R.string.panic_done))
                } else {
                    messages.error(uiText(R.string.panic_incomplete, left))
                }
            } finally {
                mutableState.update { it.copy(erasing = false) }
            }
        }
    }
}

/** What a panic did NOT erase, in words — null when it erased everything. */
internal fun notErased(report: PanicService.Report): UiText? = listOfNotNull(
    R.string.panic_part_vault.takeUnless { report.vault },
    R.string.panic_part_preferences.takeUnless { report.preferences },
    R.string.panic_part_cache.takeUnless { report.cache },
    R.string.panic_part_history.takeUnless { report.history },
    R.string.panic_part_extractions.takeUnless { report.extractions },
    R.string.panic_part_trash.takeUnless { report.trash },
).map { uiText(it) }.reduceOrNull { line, part -> UiText.Joined(line, part, R.string.joined_comma) }

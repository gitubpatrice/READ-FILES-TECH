package com.readfilestech.read_files_tech.ui.settings

import androidx.lifecycle.ViewModel
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.panic.PanicService
import com.readfilestech.read_files_tech.ui.common.ApplicationScope
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val confirming = MutableStateFlow(false)

    /** "Erase everything?" on screen. */
    val confirmPanic: StateFlow<Boolean> = confirming.asStateFlow()

    /** A panic under way — the service's: a screen left then opened again still knows it. */
    val erasing: StateFlow<Boolean> = panic.erasing

    fun askPanic() {
        if (!erasing.value) confirming.value = true
    }

    fun cancelPanic() {
        confirming.value = false
    }

    /**
     * Erases everything, on the application's scope: leaving the screen — the very reflex of a user in
     * danger — must not stop an erasure half-way. How it ended is said wherever the user is by then; a
     * panic that failed outright says that nothing is known erased.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // said to the user as "not erased"
    fun panic() {
        confirming.value = false
        if (erasing.value) return
        scope.launch {
            val report = try {
                panic.wipeAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val left = notErased(report)
            if (left == null) {
                messages.info(uiText(R.string.panic_done))
            } else {
                messages.error(uiText(R.string.panic_incomplete, left))
            }
        }
    }
}

/** What a panic did NOT erase, in words — null when it erased everything; every part when it failed outright. */
internal fun notErased(report: PanicService.Report?): UiText? = listOfNotNull(
    R.string.panic_part_vault.takeUnless { report?.vault == true },
    R.string.panic_part_preferences.takeUnless { report?.preferences == true },
    R.string.panic_part_cache.takeUnless { report?.cache == true },
    R.string.panic_part_history.takeUnless { report?.history == true },
    R.string.panic_part_extractions.takeUnless { report?.extractions == true },
    R.string.panic_part_trash.takeUnless { report?.trash == true },
).map { uiText(it) }.reduceOrNull { line, part -> UiText.Joined(line, part, R.string.joined_comma) }

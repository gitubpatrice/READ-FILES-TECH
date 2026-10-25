package com.readfilestech.read_files_tech.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.StorageVisibility
import com.readfilestech.read_files_tech.data.outputs.OutputSettings
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The outputs' folder as the settings show it.
 *
 * @param effective where the outputs go now: [configured], or the fallback that took them; null when none did.
 * @param reachable whether [effective] can be opened in the explorer — not the app's private fallback.
 * @param accessGranted whether the app may write all over the storage: when not, the cause of a fallback.
 */
data class OutputFolderState(
    val configured: File,
    val isDefault: Boolean,
    val defaultFolder: File,
    val effective: File?,
    val reachable: Boolean,
    val accessGranted: Boolean,
    val autoShare: Boolean,
)

/** The output folder and the automatic share of the settings (`SettingsScreen` of the 2.15.3). */
@HiltViewModel
class OutputSettingsViewModel @Inject constructor(
    private val settings: OutputSettings,
    private val outputs: OutputStorage,
    private val visibility: StorageVisibility,
    private val messages: UserMessages,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    private val current = MutableStateFlow<OutputFolderState?>(null)

    /** Null until read. */
    val state: StateFlow<OutputFolderState?> = current.asStateFlow()

    /** One change, then its reading, at a time: a reading finished late never shows the folder of before. */
    private val lock = Mutex()

    /** Read again — at each return to the screen: access to the storage may have been granted meanwhile. */
    fun refresh() = change(announce = false) { null }

    /** [path], the folder the picker gave, as the outputs' folder; the files made before stay where they are. */
    fun choose(path: String) = change {
        when (settings.choose(File(path))) {
            is OutputSettings.Choice.Accepted -> null
            OutputSettings.Choice.NotOnStorage -> R.string.settings_output_not_on_storage
            OutputSettings.Choice.AppFolder -> R.string.settings_output_app_folder
            OutputSettings.Choice.NotWritable -> R.string.settings_output_not_writable
            OutputSettings.Choice.NotSaved -> R.string.settings_not_saved
        }
    }

    fun reset() = change {
        R.string.settings_not_saved.takeUnless { settings.reset() }
    }

    fun setAutoShare(on: Boolean) = change(announce = false) {
        R.string.settings_not_saved.takeUnless { settings.setAutoShare(on) }
    }

    /**
     * Runs [apply] — which gives the message of its failure, or null — then reads the folder again. When the
     * outputs moved, says where they go now, and that what was made before stays in the old folder: nothing is
     * moved, and the picker no longer lists that one.
     */
    private fun change(announce: Boolean = true, apply: () -> Int?) {
        viewModelScope.launch {
            lock.withLock {
                val before = current.value?.effective
                val failure = withContext(io) { apply() }
                val now = withContext(io) { read() }
                current.value = now
                val after = now.effective
                when {
                    failure != null -> messages.error(uiText(failure))
                    announce && before != null && after != null && after != before ->
                        messages.info(uiText(R.string.settings_output_changed, after.path, before.path))
                }
            }
        }
    }

    private fun read(): OutputFolderState {
        val configured = outputs.basePath()
        val effective = settings.prepareFolders()
        return OutputFolderState(
            configured = configured,
            isDefault = configured == outputs.defaultBase,
            defaultFolder = outputs.defaultBase,
            effective = effective,
            reachable = effective != null && effective != outputs.privateBase,
            accessGranted = visibility.seesEverything(),
            autoShare = outputs.autoShare(),
        )
    }
}

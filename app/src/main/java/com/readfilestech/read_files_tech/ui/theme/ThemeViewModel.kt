package com.readfilestech.read_files_tech.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The theme of the settings' "Appearance": the current one, and the user's choice. */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val settings: ThemeSettings,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val current: StateFlow<ThemeMode> = settings.current

    /** Applied at once, written off the main thread (a synchronous commit). */
    fun set(mode: ThemeMode) {
        viewModelScope.launch(io) { settings.set(mode) }
    }
}

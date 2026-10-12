package com.readfilestech.read_files_tech.ui.viewer.sheet

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.sheet.Sheet
import com.readfilestech.read_files_tech.data.viewer.sheet.Workbook
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.viewer.ViewerContent
import com.readfilestech.read_files_tech.ui.viewer.ViewerErrors
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailure
import com.readfilestech.read_files_tech.ui.viewer.ViewerSources
import com.readfilestech.read_files_tech.ui.viewer.ViewerState
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * A workbook open, and the sheet it shows first ([SheetViewerViewModel.select] reads the others) — handed
 * over once, so that the screen's state is the only one to hold a sheet.
 */
class OpenWorkbook(val workbook: Workbook, private var first: SheetShown?) {
    fun takeFirst(): SheetShown? = first.also { first = null }
}

/** The sheet on screen: sheet [index] of the workbook, being read, read ([sheet]), or not ([failure]). */
data class SheetShown(val index: Int, val sheet: Sheet? = null, val failure: ViewerFailure? = null) {
    val loading: Boolean get() = sheet == null && failure == null
}

/**
 * The spreadsheet viewer: the workbook opened once off the main thread, then one sheet held at a time — the
 * one shown, read when it is chosen (the 2.15.3 decoded every sheet at once). The sheet chosen is kept across
 * a process death.
 */
@HiltViewModel
class SheetViewerViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    val source: ViewerSource? = ViewerSources.from(handle)

    val content = ViewerContent(viewModelScope, io) {
        val workbook = keep(Workbook.open(requireNotNull(source) { "no file in the route" }))
        val index = handle.get<Int>(KEY_SHEET)?.takeIf { it in workbook.sheetNames.indices } ?: 0
        // A sheet that cannot be read leaves the others within reach: its failure is the sheet's, not the file's.
        OpenWorkbook(workbook, if (workbook.sheetNames.isEmpty()) null else read(workbook, index))
    }.also(::addCloseable)

    private val mutableShown = MutableStateFlow<SheetShown?>(null)

    /** The sheet shown, null until the workbook is open. */
    val shown: StateFlow<SheetShown?> = mutableShown.asStateFlow()

    private var reading: Job? = null

    init {
        viewModelScope.launch {
            mutableShown.value = content.state.filterIsInstance<ViewerState.Ready<OpenWorkbook>>().first().content.takeFirst()
        }
    }

    /** Shows sheet [index]: the one shown before is let go first, so that only one is held. */
    fun select(index: Int) {
        val workbook = (content.state.value as? ViewerState.Ready)?.content?.workbook ?: return
        if (mutableShown.value?.index == index) return
        handle[KEY_SHEET] = index
        reading?.cancel()
        mutableShown.value = SheetShown(index)
        reading = viewModelScope.launch { mutableShown.value = withContext(io) { read(workbook, index) } }
    }

    private suspend fun read(workbook: Workbook, index: Int): SheetShown =
        loadOrFailure {
            workbook.sheet(
                index
            )
        }.fold({ SheetShown(index, sheet = it) }, { SheetShown(index, failure = ViewerErrors.failureOf(it)) })

    private companion object {
        const val KEY_SHEET = "sheet_index"
    }
}

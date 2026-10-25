package com.readfilestech.read_files_tech.ui.viewer.document

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.viewer.ViewerMessage
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold
import com.readfilestech.read_files_tech.ui.viewer.text.FontSizeMenu
import com.readfilestech.read_files_tech.ui.viewer.text.TextRows

/** The sizes the 2.15.3's document viewer offered, in points; 14 by default. */
@Suppress("MagicNumber") // the list itself is the definition
private val FONT_SIZES = listOf(12, 13, 14, 16, 18, 20)
private const val DEFAULT_FONT_SIZE = 14

/**
 * `DocxViewerScreen` of the 2.15.3: the text of a Word or OpenDocument file, selectable, from 12 to 20 pt,
 * share and "Open with…" — shown by the text viewer's rows. A document with no text (pictures only) says
 * so as its content, not as a failure: the 2.15.3 returned "Le document semble vide" as its error.
 */
@Composable
fun DocumentViewerScreen(onBack: () -> Unit, viewModel: DocumentViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    var fontSize by rememberSaveable { mutableIntStateOf(DEFAULT_FONT_SIZE) }
    val source = viewModel.source

    ViewerScaffold(
        title = source?.name.orEmpty(),
        shareable = source?.shareable,
        state = state,
        onBack = onBack,
        actions = { document -> if (document.hasText) FontSizeMenu(fontSize, FONT_SIZES) { fontSize = it } },
    ) { document, padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (document.hasText) {
                TextRows(document.document, fontSize, code = false)
            } else {
                ViewerMessage(stringResource(R.string.document_no_text))
            }
        }
    }
}

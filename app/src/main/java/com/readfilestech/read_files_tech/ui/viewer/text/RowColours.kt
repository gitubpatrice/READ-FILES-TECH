package com.readfilestech.read_files_tech.ui.viewer.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.readfilestech.read_files_tech.data.viewer.text.CodeColouring
import com.readfilestech.read_files_tech.data.viewer.text.CodeLanguage
import com.readfilestech.read_files_tech.data.viewer.text.CodeSpan
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import com.readfilestech.read_files_tech.ui.theme.CodeColors
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The syntax colours of a text's rows ([CodeColouring]), a row's computed on [dispatcher] when it shows. The
 * scan of the file starts with the first row asked for, in [scope] — the ViewModel's: it goes on while rows
 * scroll by, and ends with the viewer. The text and the Markdown viewers'.
 */
class RowColours(
    document: TextDocument,
    language: CodeLanguage,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val colouring = CodeColouring(document, language)

    // Without colours rather than without the app: a scan that fails leaves the rows plain.
    private val scan = scope.launch(dispatcher, CoroutineStart.LAZY) { loadOrFailure { colouring.scan() } }

    /** The colours of row [index]; none when they cannot be computed. */
    suspend fun of(index: Int): List<CodeSpan> {
        scan.start()
        return loadOrFailure { withContext(dispatcher) { colouring.spans(index) } }.getOrDefault(emptyList())
    }
}

/** Row [index] of [document], plain until its colours are computed, in the theme's light or dark colours. */
@Composable
internal fun ColouredRow(document: TextDocument, index: Int, colours: RowColours, style: TextStyle) {
    val text = remember(document, index) { document.row(index) }
    val spans by produceState<List<CodeSpan>?>(null, colours, index) { value = colours.of(index) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < HALF_LUMINANCE
    val shown = remember(text, spans, dark) {
        spans?.let { found ->
            buildAnnotatedString {
                append(text)
                found.forEach { addStyle(CodeColors.style(it.style, dark), it.start, it.end) }
            }
        } ?: AnnotatedString(text)
    }
    Text(shown, style = style, color = MaterialTheme.colorScheme.onSurface)
}

/** Below it, a surface is dark: the theme the app shows, whatever the system's. */
private const val HALF_LUMINANCE = 0.5f

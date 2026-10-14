package com.readfilestech.read_files_tech.ui.viewer.pdf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.pdf.OutlineEntry
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfRefusal
import com.readfilestech.read_files_tech.ui.theme.BrandColors
import com.readfilestech.read_files_tech.ui.viewer.ViewerActionsViewModel
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailure
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailureMessage
import com.readfilestech.read_files_tech.ui.viewer.ViewerScaffold
import kotlinx.coroutines.launch

/**
 * Port of `PdfViewerScreen` of the 2.15.3 (Syncfusion): the pages in one column, zoomed by a pinch or a
 * double tap; the page shown and first / previous / next / last / go to page; share and "Open with…" (the
 * frame's); "Edit in PDF Tech" where it signed (docs D-004); a password asked for an encrypted file; and
 * "Contents", the outline — where the 2.15.3's "Search" opened Syncfusion's bookmarks panel (K1).
 */
@Composable
fun PdfViewerScreen(onBack: () -> Unit, viewModel: PdfViewerViewModel = hiltViewModel()) {
    val state by viewModel.content.state.collectAsStateWithLifecycle()
    val source = viewModel.source
    val list = rememberLazyListState()
    var contents by remember { mutableStateOf(false) }
    // The screen's own scope: a jump from the panel outlives the panel, which closes as it starts.
    val scope = rememberCoroutineScope()
    ViewerScaffold(
        title = source?.name.orEmpty(),
        shareable = source?.shareable,
        state = state,
        onBack = onBack,
        actions = { viewer -> PdfActions(viewer, source?.shareable?.absolutePath) { contents = true } },
    ) { viewer, padding ->
        val stopped by viewer.connection.stopped.collectAsStateWithLifecycle()
        val phase by viewer.phase.collectAsStateWithLifecycle()
        when (val shown = if (stopped) PdfPhase.Failed(ViewerFailure.Reason(PdfViewer.textOf(PdfRefusal.READER_STOPPED))) else phase) {
            is PdfPhase.Failed -> ViewerFailureMessage(shown.failure, padding)
            is PdfPhase.Locked -> PasswordDialog(shown, onUnlock = { viewModel.unlock(viewer, it) }, onCancel = onBack)
            is PdfPhase.Open -> {
                PdfDocumentView(shown.pages, list, padding)
                if (contents) {
                    ContentsSheet(shown.pages, onGo = { page ->
                        contents = false
                        scope.launch { list.scrollToItem(page) }
                    }) { contents = false }
                }
            }
        }
    }
}

/** The bar's own actions: "Contents" when the file has an outline, "Edit in PDF Tech" when it may leave the app. */
@Composable
private fun RowScope.PdfActions(viewer: PdfViewer, path: String?, onContents: () -> Unit) {
    val phase by viewer.phase.collectAsStateWithLifecycle()
    val stopped by viewer.connection.stopped.collectAsStateWithLifecycle()
    val open = (phase as? PdfPhase.Open)?.takeUnless { stopped }
    if (open != null) {
        val outline by open.pages.outline.collectAsStateWithLifecycle()
        if (outline.isNotEmpty()) {
            IconButton(onClick = onContents) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.pdf_contents))
            }
        }
    }
    // The frame's own check (ViewerScaffold): the entry of the file shown, which PathGuard lets out — never the vault's.
    val actions: ViewerActionsViewModel = hiltViewModel()
    val leavable by actions.leavable.collectAsStateWithLifecycle()
    val context = LocalContext.current
    leavable?.takeIf { it.path == path }?.let { entry ->
        IconButton(onClick = { actions.launching.editInPdfTech(context, entry) }) {
            Icon(
                Icons.Outlined.PictureAsPdf,
                contentDescription = stringResource(R.string.action_edit_in_pdf_tech),
                tint = BrandColors.PdfTech
            )
        }
    }
}

@Composable
private fun PdfDocumentView(pages: PdfPageImages, list: LazyListState, padding: PaddingValues) {
    val count = pages.document.pageCount
    val zoom = remember(pages) { mutableStateOf(PdfZoom.NONE) }
    var screenHeight by remember { mutableIntStateOf(0) }
    val current by remember(pages) {
        derivedStateOf {
            val visible = list.layoutInfo.visibleItemsInfo.map { VisiblePage(it.index, it.offset, it.size) }
            PdfView.currentPage(visible, screenHeight, zoom.value) ?: 0
        }
    }
    var goTo by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val jump: (Int) -> Unit = { page -> scope.launch { list.scrollToItem(page.coerceIn(0, count - 1)) } }
    Column(Modifier.padding(padding).fillMaxSize()) {
        PdfPages(pages, list, zoom, Modifier.weight(1f)) { screenHeight = it }
        // As the 2.15.3: the bar of pages from two pages on.
        if (count > 1) {
            BottomAppBar {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PageButton(Icons.Filled.FirstPage, R.string.pdf_first_page, current > 0) { jump(0) }
                    PageButton(Icons.Filled.ChevronLeft, R.string.pdf_previous_page, current > 0) { jump(current - 1) }
                    TextButton(onClick = { goTo = true }) {
                        Text(stringResource(R.string.pdf_page_counter, current + 1, count), style = MaterialTheme.typography.titleSmall)
                    }
                    PageButton(Icons.Filled.ChevronRight, R.string.pdf_next_page, current < count - 1) { jump(current + 1) }
                    PageButton(Icons.AutoMirrored.Filled.LastPage, R.string.pdf_last_page, current < count - 1) { jump(count - 1) }
                }
            }
        }
    }
    if (goTo) {
        GoToPageDialog(
            count,
            onGo = { page ->
                goTo = false
                jump(page)
            },
            onDismiss = { goTo = false },
        )
    }
}

@Composable
private fun PageButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = stringResource(label)) }
}

/**
 * "Go to page": a number from 1 to [count], checked as it is typed — the 2.15.3 closed on anything and did
 * nothing on a wrong number, and disposed its field's controller during the closing animation (F25); the
 * text here lives in the dialog's own state.
 */
@Composable
private fun GoToPageDialog(count: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val page = PdfView.pageTyped(text, count)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.pdf_go_to_hint, count)) },
                isError = text.isNotBlank() && page == null,
                supportingText = { if (text.isNotBlank() && page == null) Text(stringResource(R.string.pdf_go_to_invalid, count)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { page?.let(onGo) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { page?.let(onGo) }, enabled = page != null) { Text(stringResource(R.string.pdf_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The password of an encrypted file (Syncfusion's own dialog in the 2.15.3). The field lives in this
 * dialog only — never in a saved state — and is emptied once sent; a wrong password asks again, saying so.
 */
@Composable
private fun PasswordDialog(phase: PdfPhase.Locked, onUnlock: (String) -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    val send = {
        if (password.isNotEmpty() && !phase.busy) {
            onUnlock(password)
            password = ""
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.pdf_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pdf_password_required))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !phase.busy,
                    singleLine = true,
                    label = { Text(stringResource(R.string.pdf_password_label)) },
                    isError = phase.wrong,
                    supportingText = { if (phase.wrong) Text(stringResource(R.string.pdf_password_wrong)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { send() }),
                )
                if (phase.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = send,
                enabled = password.isNotEmpty() && !phase.busy
            ) { Text(stringResource(R.string.action_open)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "Contents": the outline, a tap going to its page; an entry that leads nowhere is listed, not followed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(pages: PdfPageImages, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    val entries by pages.outline.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.pdf_contents),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn {
            itemsIndexed(entries) { _, entry -> ContentsRow(entry, onGo) }
        }
    }
}

@Composable
private fun ContentsRow(entry: OutlineEntry, onGo: (Int) -> Unit) {
    val page = entry.page
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (page != null) it.clickable { onGo(page) } else it }
            .padding(start = (16 + INDENT_DP * minOf(entry.depth, MAX_INDENTS)).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.title.ifEmpty { stringResource(R.string.pdf_contents_untitled) },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            color = if (page != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (page != null) {
            Box(Modifier.padding(start = 12.dp)) {
                Text("${page + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The indent of an outline level, and the levels indented: deeper ones line up with the last. */
private const val INDENT_DP = 16
private const val MAX_INDENTS = 6

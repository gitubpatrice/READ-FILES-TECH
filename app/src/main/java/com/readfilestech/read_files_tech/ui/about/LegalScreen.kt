package com.readfilestech.read_files_tech.ui.about

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownBlock
import com.readfilestech.read_files_tech.data.viewer.markdown.MarkdownRenderer
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.viewer.markdown.RenderedBlocks

/** [document], read with the composition — a few KB from the APK — and laid out lazily. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(document: LegalDocument?, onBack: () -> Unit, viewModel: AboutViewModel = hiltViewModel()) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(document?.title ?: R.string.feature_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (document) {
                LegalDocument.PRIVACY -> MarkdownText(R.raw.privacy) { viewModel.openLink(context, it) }
                LegalDocument.TERMS -> MarkdownText(R.raw.terms) { viewModel.openLink(context, it) }
                LegalDocument.NOTICES -> PlainText(LEGAL_ASSETS + "NOTICE")
                LegalDocument.LICENSE -> PlainText(LEGAL_ASSETS + "LICENSE")
                // A route naming no document (one restored from an older build): nothing to show.
                null -> Unit
            }
        }
    }
}

private const val LEGAL_ASSETS = "legal/"

/**
 * A Markdown text of `res/raw`, drawn by the Markdown viewer's layout. Read again when the app's language changes:
 * the resource is the one of that language.
 */
@Composable
private fun MarkdownText(raw: Int, onLink: (String) -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val text = remember(raw, locale) { context.resources.openRawResource(raw).bufferedReader().use { it.readText() } }
    val blocks by produceState<List<MarkdownBlock>?>(null, text) { value = MarkdownRenderer.render(text) }
    val shown = blocks
    if (shown == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        RenderedBlocks(shown, decode = { _, _ -> null }, onLink = onLink)
    }
}

/** A licence text in paragraphs laid out for the screen ([LicenceText]), selectable, to be copied. */
@Composable
private fun PlainText(asset: String) {
    val context = LocalContext.current
    val paragraphs = remember(asset) {
        LicenceText.paragraphs(context.assets.open(asset).bufferedReader().use { it.readText() })
    }
    SelectionContainer {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(paragraphs) { paragraph ->
                if (paragraph == LicenceText.RULE) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                } else {
                    Text(paragraph, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
        }
    }
}

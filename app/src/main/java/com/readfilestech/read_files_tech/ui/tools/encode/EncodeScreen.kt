package com.readfilestech.read_files_tech.ui.tools.encode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.tools.DecodeRefusal
import com.readfilestech.read_files_tech.data.tools.TextCodec
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.tools.ToolResult

private const val INPUT_MIN_LINES = 4
private const val INPUT_MAX_LINES = 8

/** `EncodeScreen` of the 2.15.3: Base64, URL or HTML, encoded or decoded as the text is typed, copied. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EncodeScreen(onBack: () -> Unit, viewModel: EncodeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_encode)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextCodec.entries.forEach { codec ->
                    FilterChip(
                        selected = state.codec == codec,
                        onClick = { viewModel.codec(codec) },
                        label = { Text(stringResource(codec.label)) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.encoding,
                    onClick = { viewModel.encoding(true) },
                    label = { Text(stringResource(R.string.encode_encode)) },
                )
                FilterChip(
                    selected = !state.encoding,
                    onClick = { viewModel.encoding(false) },
                    label = { Text(stringResource(R.string.encode_decode)) },
                )
            }
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::input,
                label = { Text(stringResource(R.string.encode_input)) },
                placeholder = { Text(stringResource(R.string.encode_input_hint)) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                minLines = INPUT_MIN_LINES,
                maxLines = INPUT_MAX_LINES,
                trailingIcon = {
                    if (state.input.isNotEmpty()) {
                        IconButton(onClick = viewModel::clear) {
                            Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = viewModel::swap, enabled = state.refusal == null && state.output.isNotEmpty()) {
                Icon(Icons.Outlined.SwapVert, contentDescription = null)
                Text(stringResource(R.string.encode_swap), Modifier.padding(start = 6.dp))
            }
            val refusal = state.refusal
            if (refusal != null) {
                Text(stringResource(refusal.message), color = MaterialTheme.colorScheme.error)
            } else if (state.input.isNotEmpty()) {
                ToolResult(state.output, state.output.length.toLong(), viewModel::copyText, viewModel::copied)
            }
        }
    }
}

private val TextCodec.label: Int
    get() = when (this) {
        TextCodec.BASE64 -> R.string.codec_base64
        TextCodec.URL -> R.string.codec_url
        TextCodec.HTML -> R.string.codec_html
    }

private val DecodeRefusal.message: Int
    get() = when (this) {
        DecodeRefusal.BASE64_INVALID -> R.string.encode_base64_invalid
        DecodeRefusal.PERCENT_INVALID -> R.string.encode_percent_invalid
        DecodeRefusal.NOT_UTF8 -> R.string.encode_not_utf8
    }

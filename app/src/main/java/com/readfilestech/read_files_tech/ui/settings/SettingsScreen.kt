package com.readfilestech.read_files_tech.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.ConfirmDangerDialog
import com.readfilestech.read_files_tech.ui.common.languageSettings
import com.readfilestech.read_files_tech.ui.common.startFirst
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.theme.DangerRed

/**
 * Settings (`SettingsScreen` of the 2.15.3): the app's language (Android 13+), the files the tools create — their
 * folder, the automatic share —, what the 2.x left while some is (docs D-021, D-022), and the security section, the
 * panic.
 *
 * @param picked the folder the picker handed back, for the outputs
 * @param onNavigate to the picker, or to the explorer on the outputs' folder
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    picked: PickedResults,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    outputs: OutputSettingsViewModel = hiltViewModel(),
    legacy: LegacyDataViewModel = hiltViewModel(),
) {
    val confirmPanic by viewModel.confirmPanic.collectAsStateWithLifecycle()
    val erasing by viewModel.erasing.collectAsStateWithLifecycle()
    val outputFolder by outputs.state.collectAsStateWithLifecycle()
    LaunchedEffect(picked.folder) {
        picked.folder?.let {
            picked.consume()
            outputs.choose(it)
        }
    }
    // Storage access granted in Android's settings, a card put in: what takes the outputs is read again.
    LifecycleResumeEffect(Unit) {
        outputs.refresh()
        legacy.refresh()
        onPauseOrDispose { }
    }
    val chooseTitle = stringResource(R.string.settings_output_choose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        // Where the panic's outcome is said: the messages show only on a screen that hosts them.
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Below Android 13 the app follows the phone's language: there is no per-app page to open.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val context = LocalContext.current
                SectionTitle(stringResource(R.string.settings_general))
                SettingRow(
                    icon = Icons.Outlined.Language,
                    title = stringResource(R.string.settings_language),
                    summary = stringResource(R.string.settings_language_summary),
                    onClick = { startFirst(context, languageSettings(context)) },
                )
            }
            OutputSettingsSection(
                outputs,
                outputFolder,
                onChoose = { onNavigate(Routes.picker(PickMode.FOLDER, title = chooseTitle)) },
                onOpen = { onNavigate(Routes.explorer(it.path)) },
            )
            LegacyDataSection(legacy)
            SectionTitle(stringResource(R.string.settings_security))
            SettingRow(
                icon = Icons.Outlined.WarningAmber,
                title = stringResource(R.string.panic_title),
                summary = stringResource(R.string.panic_summary),
                onClick = viewModel::askPanic,
                enabled = !erasing,
                tint = DangerRed,
            )
        }
    }
    if (confirmPanic) {
        ConfirmDangerDialog(
            title = stringResource(R.string.panic_confirm_title),
            message = stringResource(R.string.panic_confirm_body),
            confirmLabel = stringResource(R.string.panic_confirm),
            onConfirm = viewModel::panic,
            onDismiss = viewModel::cancelPanic,
        )
    }
    if (erasing) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.panic_erasing)) },
            text = { CircularProgressIndicator() },
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
internal fun SettingRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (tint == Color.Unspecified) LocalContentColor.current else tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

package com.readfilestech.read_files_tech.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.AppSnackbarHost
import com.readfilestech.read_files_tech.ui.PickedResults
import com.readfilestech.read_files_tech.ui.common.ConfirmDangerDialog
import com.readfilestech.read_files_tech.ui.common.DialogAction
import com.readfilestech.read_files_tech.ui.common.SectionCard
import com.readfilestech.read_files_tech.ui.common.SectionTitle
import com.readfilestech.read_files_tech.ui.common.languageSettings
import com.readfilestech.read_files_tech.ui.common.startFirst
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.theme.ThemeMode
import com.readfilestech.read_files_tech.ui.theme.ThemeViewModel
import com.readfilestech.read_files_tech.ui.theme.dangerContent

/**
 * Settings (`SettingsScreen` of the 2.15.3), laid out as the other apps': the appearance first — the theme, which
 * the 2.15.x chose from the home, and the app's language (Android 13+) —, the files the tools create — their
 * folder, the automatic share —, what the 2.x left while some is (docs D-021, D-022), the security section, the
 * panic, and About last. One card per section, its lines parted by dividers.
 *
 * @param picked the folder the picker handed back, for the outputs
 * @param onNavigate to the picker, to the explorer on the outputs' folder, to About
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
    themes: ThemeViewModel = hiltViewModel(),
) {
    val confirmPanic by viewModel.confirmPanic.collectAsStateWithLifecycle()
    val erasing by viewModel.erasing.collectAsStateWithLifecycle()
    val outputFolder by outputs.state.collectAsStateWithLifecycle()
    val theme by themes.current.collectAsStateWithLifecycle()
    var choosingTheme by rememberSaveable { mutableStateOf(false) }
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
            Modifier.padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            AppearanceSection(theme, onChooseTheme = { choosingTheme = true })
            OutputSettingsSection(
                outputs,
                outputFolder,
                onChoose = { onNavigate(Routes.picker(PickMode.FOLDER, title = chooseTitle)) },
                onOpen = { onNavigate(Routes.explorer(it.path)) },
            )
            LegacyDataSection(legacy)
            SectionTitle(stringResource(R.string.settings_security))
            // The one card circled in red (Notes Tech): nothing else of the screen destroys anything.
            SectionCard(border = dangerContent.copy(alpha = DANGER_BORDER_ALPHA)) {
                SettingRow(
                    icon = Icons.Outlined.WarningAmber,
                    title = stringResource(R.string.panic_title),
                    summary = stringResource(R.string.panic_summary),
                    onClick = viewModel::askPanic,
                    enabled = !erasing,
                    tint = dangerContent,
                )
            }
            SectionTitle(stringResource(R.string.feature_about))
            SectionCard {
                SettingRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.settings_about),
                    summary = stringResource(R.string.settings_about_summary),
                    onClick = { onNavigate(Routes.feature(Feature.ABOUT)) },
                    trailing = Icons.Filled.ChevronRight,
                )
            }
        }
    }
    if (choosingTheme) {
        ThemeDialog(
            current = theme,
            onPick = {
                choosingTheme = false
                themes.set(it)
            },
            onDismiss = { choosingTheme = false },
        )
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

/** The theme, then the app's language: Android has a per-app language page from Android 13 only. */
@Composable
private fun AppearanceSection(theme: ThemeMode, onChooseTheme: () -> Unit) {
    SectionTitle(stringResource(R.string.settings_appearance))
    SectionCard {
        SettingRow(
            icon = themeIcon(theme),
            title = stringResource(R.string.settings_theme),
            value = stringResource(themeLabel(theme)),
            onClick = onChooseTheme,
        )
        // Below Android 13 the app follows the phone's language: there is no per-app page to open.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val context = LocalContext.current
            HorizontalDivider()
            SettingRow(
                icon = Icons.Outlined.Language,
                title = stringResource(R.string.settings_language),
                summary = stringResource(R.string.settings_language_summary),
                onClick = { startFirst(context, languageSettings(context)) },
            )
        }
    }
}

/** System, light, dark — the current one checked; a tap applies it and closes, as Notes Tech's. */
@Composable
private fun ThemeDialog(current: ThemeMode, onPick: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    // The row is the radio button: one target of 48 dp, read by TalkBack as one choice.
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = mode == current, role = Role.RadioButton, onClick = { onPick(mode) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == current, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(themeLabel(mode)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { DialogAction(stringResource(R.string.action_close), onClick = onDismiss) },
    )
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private fun themeIcon(mode: ThemeMode): ImageVector = when (mode) {
    ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Outlined.LightMode
    ThemeMode.DARK -> Icons.Outlined.DarkMode
}

/**
 * A line of a settings card: [icon], [title], and under it a [summary] — a path in [monospace], wrapped and
 * selectable to be copied — or, at the right, the current [value] in the primary colour; tappable when [onClick]
 * is given, [trailing] then saying where it leads. Disabled, dimmed. [tint] colours the icon and the title.
 */
@Composable
internal fun SettingRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    value: String? = null,
    monospace: Boolean = false,
    trailing: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
) {
    val tap = if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, onClick = onClick)
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val content = (if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint).copy(alpha = alpha)
    Row(
        tap.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = content)
            if (summary != null) {
                val style = MaterialTheme.typography.bodySmall.let { if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it }
                val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                // A path can be long: it wraps, and can be selected to be copied.
                if (monospace) {
                    SelectionContainer { Text(summary, style = style, color = color) }
                } else {
                    Text(summary, style = style, color = color)
                }
            }
        }
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        if (trailing != null) Icon(trailing, contentDescription = null, tint = content)
    }
}

private const val DISABLED_ALPHA = 0.38f

/** The panic card's border (Notes Tech's): the danger red at 30 %, a frame rather than an alarm. */
private const val DANGER_BORDER_ALPHA = 0.3f

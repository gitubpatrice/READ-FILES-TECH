package com.readfilestech.read_files_tech.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.ui.LocalStorageSettings
import java.io.File

/**
 * "Files you create" of the settings (`SettingsScreen` of the 2.15.3): the output folder — changed, opened in
 * the explorer, back to the default — what really takes the files when it cannot, the automatic share, and
 * what each subfolder holds.
 *
 * @param onChoose opens the picker for a folder; its answer goes to [OutputSettingsViewModel.choose].
 * @param onOpen the folder, in the explorer.
 */
@Composable
internal fun OutputSettingsSection(
    viewModel: OutputSettingsViewModel,
    state: OutputFolderState?,
    onChoose: () -> Unit,
    onOpen: (File) -> Unit,
) {
    SectionTitle(stringResource(R.string.settings_outputs))
    if (state == null) return
    Card(Modifier.fillMaxWidth()) {
        SettingLine(
            icon = Icons.Outlined.Folder,
            title = stringResource(R.string.settings_output_folder),
            summary = state.configured.path,
            monospace = true,
            trailing = Icons.Outlined.Edit,
            onClick = onChoose,
        )
        FallbackBanner(state)
        HorizontalDivider()
        // The fallback the outputs went to, or the folder chosen when none took them: the explorer then says why.
        val shown = if (state.effective == null) state.configured else state.effective.takeIf { state.reachable }
        SettingLine(
            icon = Icons.Outlined.FolderOpen,
            title = stringResource(R.string.settings_output_open),
            trailing = Icons.AutoMirrored.Filled.ArrowForward,
            onClick = { shown?.let(onOpen) },
            enabled = shown != null,
        )
        HorizontalDivider()
        SettingLine(
            icon = Icons.Outlined.Restore,
            title = stringResource(R.string.settings_output_reset),
            summary = state.defaultFolder.path,
            monospace = true,
            onClick = viewModel::reset,
            enabled = !state.isDefault,
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Switch) { viewModel.setAutoShare(!state.autoShare) }
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Share, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_auto_share), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.settings_auto_share_summary), style = MaterialTheme.typography.bodySmall)
            }
            // The row is the control: the switch only shows it, and TalkBack reads one element.
            Switch(checked = state.autoShare, onCheckedChange = null)
        }
    }
    SectionTitle(stringResource(R.string.settings_subfolders))
    Card(Modifier.fillMaxWidth()) {
        OutputCategory.entries.filter(OutputCategory::written).forEach { category ->
            shownAs(category)?.let { (icon, description) ->
                SettingLine(icon = icon, title = category.folderName, summary = stringResource(description))
            }
        }
    }
    Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
        Text(stringResource(R.string.settings_outputs_info), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Where the files go when the chosen folder cannot take them — said, with the way back when the storage access
 * is what is missing. The 2.15.3 showed two boxes for one cause, and named a fallback path with `<package>` in it.
 */
@Composable
private fun FallbackBanner(state: OutputFolderState) {
    val effective = state.effective
    if (effective == state.configured) return
    val text = when {
        effective == null -> stringResource(R.string.settings_output_nowhere)
        state.reachable -> stringResource(R.string.settings_output_fallback, effective.path)
        else -> stringResource(R.string.settings_output_fallback_private)
    }
    val storage = LocalStorageSettings.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = if (state.accessGranted) 12.dp else 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            if (!state.accessGranted) {
                TextButton(onClick = storage.request, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.action_allow))
                }
            }
        }
    }
}

/** A line of a card: [icon], [title], [summary] under it; tappable when [onClick] is given. */
@Composable
private fun SettingLine(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    monospace: Boolean = false,
    trailing: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val tap = if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, onClick = onClick)
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        tap.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
        Icon(icon, contentDescription = null, tint = color)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (summary != null) {
                val style = MaterialTheme.typography.bodySmall.let { if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it }
                // A path can be long: it wraps, and can be selected to be copied.
                SelectionContainer { Text(summary, style = style, color = color) }
            }
        }
        if (trailing != null) Icon(trailing, contentDescription = null, tint = color)
    }
}

private const val DISABLED_ALPHA = 0.38f

/** The icon and the description of a category the settings list; null for one no tool writes any more. */
private fun shownAs(category: OutputCategory): Pair<ImageVector, Int>? = when (category) {
    OutputCategory.SCANS -> Icons.Outlined.DocumentScanner to R.string.output_category_scans
    OutputCategory.CONVERSIONS -> Icons.Outlined.Transform to R.string.output_category_conversions
    OutputCategory.COMPRESSIONS -> Icons.Outlined.Compress to R.string.output_category_compressions
    OutputCategory.SIGNATURES -> null
    OutputCategory.NO_EXIF -> Icons.Outlined.CleaningServices to R.string.output_category_no_exif
    OutputCategory.OCR -> Icons.AutoMirrored.Outlined.TextSnippet to R.string.output_category_ocr
    OutputCategory.VAULT_BACKUPS -> Icons.Outlined.Shield to R.string.output_category_vault_backups
    OutputCategory.EXTRACTIONS -> Icons.Outlined.Unarchive to R.string.output_category_extractions
}

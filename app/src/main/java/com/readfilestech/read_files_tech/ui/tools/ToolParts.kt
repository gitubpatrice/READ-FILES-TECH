package com.readfilestech.read_files_tech.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.ui.common.formatBytes

/**
 * A tool's screen before a file is chosen: its icon, its name ([title], none where the screen shows it beside
 * the intro: Diff), what it does — or, [isError], why the file chosen did not open (the code editor) —, and the
 * button to choose one — [chooseLabel], "Choose files" for a tool that takes several; none ([onChoose] null)
 * where the screen has its own buttons: the OCR, the scanner, Diff —, then [more] (the CSV tools' "Merge").
 */
@Composable
fun ToolIntro(
    icon: ImageVector,
    title: String?,
    text: String,
    onChoose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    chooseLabel: String = stringResource(R.string.action_choose_file),
    more: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(88.dp), tint = MaterialTheme.colorScheme.primary)
        title?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
            textAlign = TextAlign.Center,
        )
        onChoose?.let { choose ->
            Button(onClick = choose) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(chooseLabel)
            }
        }
        more()
    }
}

/** The file a tool works on: its name, its size, and "Change" — not while the tool is at work on it. */
@Composable
fun ChosenFileRow(file: FileEntry, onChange: () -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onChange, enabled = enabled) { Text(stringResource(R.string.action_change)) }
    }
}

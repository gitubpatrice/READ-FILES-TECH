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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.ui.common.formatBytes

/**
 * A tool's screen before a file is chosen: its icon, its name, what it does, and the button to choose one
 * — [chooseLabel], "Choose files" for a tool that takes several.
 */
@Composable
fun ToolIntro(
    icon: ImageVector,
    title: String,
    text: String,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
    chooseLabel: String = stringResource(R.string.action_choose_file),
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(88.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onChoose) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(chooseLabel)
        }
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

package com.readfilestech.read_files_tech.ui.tools.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.MIB
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.resolve

/**
 * The file changed since an editor read it: asked before it is overwritten — the code editor's and the CSV
 * editor's, which offered nothing and left its edits stuck. [keptInHistory]: its version on disk goes to the
 * history first; when it is too large for it, the dialog says it will be lost.
 */
@Composable
internal fun ChangedOnDiskDialog(
    name: String,
    saving: Boolean,
    failure: UiText?,
    keptInHistory: Boolean,
    onOverwrite: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text(stringResource(R.string.editor_changed_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (keptInHistory) {
                        stringResource(R.string.editor_changed_message)
                    } else {
                        stringResource(R.string.editor_changed_message_lost, (FileCaps.EDITOR_HISTORY_COPY / MIB).toInt())
                    },
                )
                failure?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = onOverwrite, enabled = !saving) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.editor_overwrite))
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(R.string.action_cancel)) } },
    )
}

package com.readfilestech.read_files_tech.ui.outputs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shares the output at [path] once it is made, when "share automatically after creation" is on (the view
 * model sets [path] then), and calls [onShared] once it is handed over — not before: marked done first, its
 * own recomposition would cancel this effect.
 */
@Composable
fun AutoShare(path: String?, launching: EntryLaunching, onShared: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(path) {
        path?.let {
            val entry = withContext(Dispatchers.IO) { DirectoryListing.entryOf(File(it)) }
            launching.share(context, listOf(entry))
            onShared()
        }
    }
}

/**
 * A tool's output, saved: what it is ([title], [body], then [details] — the scanner's, Compress's, Remove
 * EXIF's), where it is, its folder to open, and the [OutputActionsRow] under it.
 */
@Composable
fun OutputSavedCard(
    title: String,
    body: String,
    path: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    details: @Composable ColumnScope.() -> Unit = {},
) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
            }
            details()
            Text(body, style = MaterialTheme.typography.bodySmall)
            Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { File(path).parent?.let { onNavigate(Routes.explorer(path = it)) } }) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Text(stringResource(R.string.action_open_folder), Modifier.padding(start = 6.dp))
            }
            OutputActionsRow(path, onNavigate)
        }
    }
}

package com.readfilestech.read_files_tech.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A button whose action takes a while — a save, an overwrite: a spinner in place of its label meanwhile, and still
 * named for TalkBack, which read a nameless progress bar (GPT review, phase 8).
 */
@Composable
fun BusyButton(text: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.semantics { if (busy) contentDescription = text }) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(text)
    }
}

/** [BusyButton] as an icon of a top bar; [enabled] when more than [busy] keeps it off. */
@Composable
fun BusyIconButton(icon: ImageVector, text: String, busy: Boolean, onClick: () -> Unit, enabled: Boolean = !busy) {
    val named = Modifier.semantics { if (busy) contentDescription = text }
    IconButton(onClick = onClick, enabled = enabled, modifier = named) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(icon, contentDescription = text)
    }
}

package com.readfilestech.read_files_tech.ui.tools

import android.content.ClipData
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.viewer.json.JsonViewerViewModel
import kotlinx.coroutines.launch

/** Characters of a result laid out on the screen: a text view lays out what it is given whole. */
private const val SHOWN_CHARS = 20_000

/**
 * What copying a result of [length] characters, whose first ones are [text], puts in the clipboard — or
 * null, said, when it is longer than the clipboard takes ([JsonViewerViewModel.COPY_CHARS], the JSON
 * viewer's bound: one Binder transaction carries it).
 */
fun copyableResult(text: String, length: Long, messages: UserMessages): String? {
    if (length <= JsonViewerViewModel.COPY_CHARS && text.length.toLong() == length) return text
    messages.error(UiText.Plural(R.plurals.json_too_long_to_copy, JsonViewerViewModel.COPY_CHARS))
    return null
}

fun resultCopied(messages: UserMessages) = messages.info(uiText(R.string.tool_result_copied))

/**
 * A tool's text result: its length, a copy button, the first [SHOWN_CHARS] of [text] — selectable — and,
 * when that is not all of it, a line saying so.
 *
 * @param text the result, or its beginning
 * @param length the whole result's, in characters
 * @param copy the text to copy, or null when it cannot be (already said)
 */
@Composable
fun ToolResult(text: String, length: Long, copy: () -> String?, onCopied: () -> Unit) {
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tool_result), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.padding(4.dp))
            Text(
                pluralStringResource(R.plurals.tool_result_length, length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), length),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                copy()?.let { value ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, value))) }
                    onCopied()
                }
            }, enabled = length > 0) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.action_copy))
            }
        }
        // Never ending on the first half of a surrogate pair.
        val cut = if (text.length > SHOWN_CHARS && text[SHOWN_CHARS - 1].isHighSurrogate()) SHOWN_CHARS - 1 else SHOWN_CHARS
        val shown = text.take(cut)
        SelectionContainer {
            Text(
                shown,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
        }
        if (shown.length < length) {
            Text(
                pluralStringResource(R.plurals.tool_result_beginning, shown.length, shown.length, length),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

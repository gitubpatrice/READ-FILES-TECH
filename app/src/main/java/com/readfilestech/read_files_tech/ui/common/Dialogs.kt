package com.readfilestech.read_files_tech.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.theme.dangerContent

/**
 * Asks for a name (`promptName` of the 2.15.x): a new folder, a new name. The name before the
 * extension is preselected when renaming. Confirms the TRIMMED text; an empty one cannot be confirmed.
 */
@Composable
fun NameDialog(title: String, confirmLabel: String, initial: String, hint: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val dot = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, dot)))
    }
    val focus = remember { FocusRequester() }
    val name = value.text.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = hint?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (name.isNotEmpty()) onConfirm(name) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { BrandButton(onClick = { onConfirm(name) }, enabled = name.isNotEmpty()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * Confirms a DEFINITIVE deletion, or another loss (`confirmDelete` of the 2.15.x), in the form of the other apps
 * (Notes Tech's `DialogueDestructif`): the destructive action OUTLINED in red, of the same weight as "Cancel" — a
 * solid button made it the one tapped by reflex —, and the focus on "Cancel", so a reflex "Enter" cancels. The
 * message scrolls: a long one never pushes the actions off the screen.
 *
 * @param dismissLabel what "no" says when it is not "Cancel" ("Keep").
 */
@Composable
fun ConfirmDangerDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = null,
) {
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { DialogAction(confirmLabel, onClick = onConfirm, color = dangerContent) },
        dismissButton = {
            val no = dismissLabel ?: stringResource(R.string.action_cancel)
            DialogAction(no, onClick = onDismiss, modifier = Modifier.focusRequester(focus))
            // In the dialog's own composition, where the requester has its node.
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
    )
}

/**
 * An action of a dialog (Notes Tech's `ActionDeDialogue`): outlined, its border the colour of its text — a
 * destructive one red ([dangerContent]), so it stays the more alarming of two actions of the same weight. 12 dp
 * across like a text button, 4 dp high: a dialog's height goes to its actions.
 */
@Composable
fun DialogAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    // The border follows the state: a refused action whose border stayed sharp would read as available.
    val tint = if (enabled) color else color.copy(alpha = DISABLED_ALPHA)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        border = BorderStroke(1.dp, tint),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = tint),
    ) { Text(text) }
}

/** Material 3's alpha for disabled content. */
private const val DISABLED_ALPHA = 0.38f

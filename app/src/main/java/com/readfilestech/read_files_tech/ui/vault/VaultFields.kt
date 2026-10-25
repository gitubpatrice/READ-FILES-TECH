package com.readfilestech.read_files_tech.ui.vault

import android.view.View
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.readfilestech.read_files_tech.R

/**
 * A password of the vault: masked, with the eye that shows it; no suggestion, no correction, no
 * autofill; neither cut nor copy while it is masked (Compose offers them only on a visible text) — the
 * 2.15.3's `TextField(obscureText, enableSuggestions: false, autocorrect: false, autofillHints: [],
 * enableInteractiveSelection: _show)`.
 *
 * Not the secure text field of Compose: it declares itself a password to the autofill service, which would
 * then offer to save the vault's password — in a password manager that syncs it. The text lives in a
 * plain `remember` of the caller, never in a saved state: the system writes those to disk when the process
 * goes.
 */
@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    focusFirst: Boolean = false,
    enabled: Boolean = true,
    focus: FocusRequester = remember { FocusRequester() },
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focus),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (visible) R.string.vault_password_hide else R.string.vault_password_show),
                )
            }
        },
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        // "Next" is the caller's, which gives the focus to the confirmation by its requester: the default
        // moves it to the next focusable, this field's own eye (seen on the S9).
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
    )
    if (focusFirst) LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * The window this is composed in kept out of autofill while it shows — the vault's screens, and each of its
 * dialogs, which are windows of their own: nothing of them is offered to an autofill service, to fill or to
 * save.
 */
@Composable
internal fun NoAutofill() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.importantForAutofill
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose { view.importantForAutofill = before }
    }
}

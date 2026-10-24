package com.readfilestech.read_files_tech.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.theme.CautionAmber
import com.readfilestech.read_files_tech.ui.theme.DangerRed

/** The 2.15.3's floor: a password of at least 8 characters, for the vault as for a backup. */
internal const val PASSWORD_MIN_CHARS = 8

/** Why [password] (and its [confirmation], when asked) is refused before anything is derived, or null. */
internal fun passwordProblem(password: String, confirmation: String?): Int? = when {
    password.length < PASSWORD_MIN_CHARS -> R.string.vault_password_too_short
    confirmation != null && confirmation != password -> R.string.vault_password_mismatch
    else -> null
}

/** `_SetupScreen` of the 2.15.3: the password twice — the eye shows both —, and no way back if it is lost. */
@Composable
internal fun SetupForm(state: VaultState, onCreate: (String) -> Unit, modifier: Modifier = Modifier) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    val creating = state.work == VaultWork.Creating
    val confirmFocus = remember { FocusRequester() }
    // Typed and left (a call, Home): not in the fields for whoever takes the phone.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        password = ""
        confirmation = ""
        visible = false
    }
    val submit = {
        problem = passwordProblem(password, confirmation)
        if (problem == null) onCreate(password)
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.vault_setup_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        PasswordField(
            value = password,
            onValueChange = {
                password = it
                problem = null
            },
            label = stringResource(R.string.vault_password),
            visible = visible,
            onToggleVisible = { visible = !visible },
            modifier = Modifier.fillMaxWidth(),
            supportingText = stringResource(R.string.vault_password_min),
            imeAction = ImeAction.Next,
            onImeAction = { confirmFocus.requestFocus() },
            enabled = !creating,
        )
        PasswordField(
            value = confirmation,
            onValueChange = {
                confirmation = it
                problem = null
            },
            label = stringResource(R.string.vault_password_confirm),
            visible = visible,
            onToggleVisible = { visible = !visible },
            modifier = Modifier.fillMaxWidth(),
            onImeAction = submit,
            enabled = !creating,
            focus = confirmFocus,
        )
        FormError(problem?.let { stringResource(it) } ?: state.error?.resolve())
        Button(onClick = submit, enabled = !creating, modifier = Modifier.fillMaxWidth()) {
            ButtonIcon(creating) { Icon(Icons.Outlined.Check, contentDescription = null) }
            Text(stringResource(R.string.vault_create))
        }
        Warning(stringResource(R.string.vault_no_recovery))
    }
}

/** `_UnlockScreen` of the 2.15.3; "Reset" erases a vault whose password is lost, after a confirmation. */
@Composable
internal fun UnlockForm(state: VaultState, onUnlock: (String) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val unlocking = state.work == VaultWork.Unlocking
    // Tried, it leaves the field: a refused one, the eye touched, showed the password or one a letter away.
    val submit = {
        if (password.isNotEmpty()) {
            onUnlock(password)
            password = ""
            visible = false
        }
    }
    // Typed and left (a call, Home): not in the field for whoever takes the phone.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        password = ""
        visible = false
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(20.dp))
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.vault_locked_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        PasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.vault_password),
            visible = visible,
            onToggleVisible = { visible = !visible },
            modifier = Modifier.fillMaxWidth(),
            onImeAction = submit,
            focusFirst = true,
            enabled = !unlocking,
        )
        FormError(state.error?.resolve())
        Button(onClick = submit, enabled = !unlocking && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            ButtonIcon(unlocking) { Icon(Icons.Outlined.LockOpen, contentDescription = null) }
            Text(stringResource(R.string.vault_unlock))
        }
        Spacer(Modifier.size(16.dp))
        ResetButton(onReset, enabled = !unlocking)
    }
}

/** A vault whose data cannot be read (its sentinel, its salt, its flags): said, and the reset offered. */
@Composable
internal fun BrokenVault(onReset: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(stringResource(R.string.vault_corrupted), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        ResetButton(onReset, enabled = true)
    }
}

@Composable
private fun ResetButton(onReset: () -> Unit, enabled: Boolean) {
    TextButton(onClick = onReset, enabled = enabled) {
        Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = DangerRed)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.vault_reset), color = DangerRed)
    }
}

@Composable
private fun FormError(text: String?) {
    if (text != null) {
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
    }
}

/** A spinner in place of the button's icon while its work runs. */
@Composable
private fun ButtonIcon(working: Boolean, icon: @Composable () -> Unit) {
    if (working) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
    } else {
        icon()
    }
    Spacer(Modifier.size(8.dp))
}

@Composable
private fun Warning(text: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(CautionAmber.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .border(1.dp, CautionAmber.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = CautionAmber, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

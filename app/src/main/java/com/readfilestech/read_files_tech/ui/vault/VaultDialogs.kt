package com.readfilestech.read_files_tech.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.ui.common.ConfirmDangerDialog
import com.readfilestech.read_files_tech.ui.theme.DangerRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The long operations that hold the screen: creating (Argon2, seconds), backing up, restoring. */
@Composable
internal fun VaultWorkDialog(work: VaultWork?) {
    val (title, detail) = when (work) {
        VaultWork.Creating -> R.string.vault_creating to R.string.vault_creating_detail
        VaultWork.BackingUp -> R.string.vault_backing_up to null
        VaultWork.Restoring -> R.string.vault_restoring to null
        else -> return
    }
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = { },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(title))
                if (detail != null) {
                    Text(
                        stringResource(detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** The question or the report [dialog] names, its answers wired to [viewModel]. */
@Composable
internal fun VaultDialogs(dialog: VaultDialog?, viewModel: VaultViewModel) {
    when (dialog) {
        null -> Unit
        VaultDialog.ConfirmReset -> ConfirmDangerDialog(
            title = stringResource(R.string.vault_reset_title),
            message = stringResource(R.string.vault_reset_message),
            confirmLabel = stringResource(R.string.vault_reset_confirm),
            onConfirm = viewModel::reset,
            onDismiss = viewModel::dismiss,
        )
        is VaultDialog.Namesake -> Question(
            title = stringResource(R.string.vault_namesake_title),
            message = stringResource(R.string.vault_namesake_message, dialog.name),
            yes = stringResource(R.string.vault_replace),
            no = stringResource(R.string.vault_keep),
            danger = false,
            onAnswer = viewModel::answer,
        )
        is VaultDialog.ExportNamesake -> Question(
            title = stringResource(R.string.vault_namesake_title),
            message = stringResource(R.string.vault_export_namesake_message, dialog.name),
            yes = stringResource(R.string.vault_replace),
            no = stringResource(R.string.vault_keep),
            danger = true,
            onAnswer = viewModel::answer,
        )
        is VaultDialog.ConfirmDelete -> ConfirmDangerDialog(
            title = stringResource(R.string.vault_delete_title),
            message = stringResource(R.string.vault_delete_message, dialog.item.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { viewModel.delete(dialog.item) },
            onDismiss = viewModel::dismiss,
        )
        VaultDialog.BackupPassword -> PasswordDialog(
            title = stringResource(R.string.vault_backup),
            info = stringResource(R.string.vault_backup_password_info),
            confirm = true,
            submitLabel = stringResource(R.string.vault_backup_go),
            onSubmit = viewModel::backup,
            onDismiss = viewModel::dismiss,
        )
        is VaultDialog.UnexpectedExtension -> Question(
            title = stringResource(R.string.vault_restore_extension_title),
            message = stringResource(R.string.vault_restore_extension_message),
            yes = stringResource(R.string.vault_continue),
            no = stringResource(R.string.action_cancel),
            danger = false,
            onAnswer = { yes -> if (yes) viewModel.restoreAnyway(dialog.path) else viewModel.dismiss() },
        )
        is VaultDialog.RestorePassword -> PasswordDialog(
            title = stringResource(R.string.vault_restore),
            info = stringResource(R.string.vault_restore_password_info),
            confirm = false,
            submitLabel = stringResource(R.string.vault_restore_go),
            onSubmit = { viewModel.restore(dialog.path, it) },
            onDismiss = viewModel::dismiss,
        )
        is VaultDialog.RestoreAllThere -> ConfirmDangerDialog(
            title = stringResource(R.string.vault_restore_nothing_title),
            message = pluralStringResource(R.plurals.vault_restore_nothing_message, dialog.count, dialog.count) + "\n\n" +
                stringResource(R.string.vault_restore_replace_message),
            confirmLabel = stringResource(R.string.vault_replace),
            onConfirm = viewModel::restoreReplacing,
            onDismiss = viewModel::dismiss,
        )
        is VaultDialog.BackupDone -> BackupDone(dialog.path, viewModel::dismiss)
        is VaultDialog.Repair -> RepairReport(dialog, onSeen = viewModel::repairSeen, onDismiss = viewModel::dismiss)
    }
}

/** A yes or no; [danger] paints "yes" red and puts the focus on "no", so a reflex "Enter" says no. */
@Composable
private fun Question(title: String, message: String, yes: String, no: String, danger: Boolean, onAnswer: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            val colors = if (danger) {
                ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = Color.White)
            } else {
                ButtonDefaults.buttonColors()
            }
            Button(onClick = { onAnswer(true) }, colors = colors) { Text(yes) }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }, modifier = Modifier.focusRequester(focus)) { Text(no) }
            // In the dialog's own composition, where the requester has its node.
            if (danger) LaunchedEffect(Unit) { focus.requestFocus() }
        },
    )
}

/**
 * `_PasswordDialog` of the 2.15.3: a backup's password, twice when it is chosen ([confirm]). It scrolls: with
 * the keyboard up, the 2.15.3's buttons covered the second field (seen on a device, 2026-08-10). Its own
 * window, kept out of autofill too.
 */
@Composable
private fun PasswordDialog(
    title: String,
    info: String,
    confirm: Boolean,
    submitLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<Int?>(null) }
    val confirmFocus = remember { FocusRequester() }
    val submit = {
        problem = passwordProblem(password, confirmation.takeIf { confirm })
        if (problem == null) onSubmit(password)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            NoAutofill()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    supportingText = if (confirm) stringResource(R.string.vault_password_min) else null,
                    imeAction = if (confirm) ImeAction.Next else ImeAction.Done,
                    onImeAction = { if (confirm) confirmFocus.requestFocus() else submit() },
                    focusFirst = true,
                )
                if (confirm) {
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
                        focus = confirmFocus,
                    )
                }
                problem?.let { refused ->
                    Text(stringResource(refused), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = submit) { Text(submitLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Where the backup went, said before anything is done with it; its share offered, never imposed (2.15.3). */
@Composable
private fun BackupDone(path: String, onDismiss: () -> Unit, launch: VaultLaunchViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_backup_done_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.vault_backup_done_body))
                Text(File(path).name, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    val entry = withContext(Dispatchers.IO) { DirectoryListing.entryOf(File(path)) }
                    launch.launching.share(context, listOf(entry))
                }
            }) { Text(stringResource(R.string.action_share)) }
        },
    )
}

/**
 * The repair of the files the 2.5.5 to 2.15.2 encrypted under a zeroed key: kept until seen — dismissed
 * without "OK", it comes back at the next unlock.
 */
@Composable
private fun RepairReport(report: VaultDialog.Repair, onSeen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_repair_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.vault_repair_intro))
                if (report.repaired.isNotEmpty()) Text(stringResource(R.string.vault_repair_repaired, bullets(report.repaired)))
                if (report.unexamined.isNotEmpty()) Text(stringResource(R.string.vault_repair_unexamined, bullets(report.unexamined)))
            }
        },
        confirmButton = { Button(onClick = onSeen) { Text(stringResource(R.string.action_ok)) } },
    )
}

private fun bullets(names: List<String>): String = names.joinToString("\n") { "• $it" }

package com.readfilestech.read_files_tech.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.readfilestech.read_files_tech.ui.common.UserMessage

/** The app's one message host: every screen's Scaffold shows it, so a message survives its screen. */
val LocalAppSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("AppRoot provides the snackbar host") }

/** A message as the snackbar draws it: an error in the error colours, never like a success. */
class AppSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    val isError: Boolean,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = true

    // With an action ("Undo") the message stays longer: the user must have time to take it back.
    override val duration: SnackbarDuration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
}

/** The host each screen puts in its Scaffold. */
@Composable
fun AppSnackbarHost() {
    SnackbarHost(LocalAppSnackbar.current) { data ->
        val error = (data.visuals as? AppSnackbarVisuals)?.isError == true
        if (error) {
            Snackbar(
                data,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionColor = MaterialTheme.colorScheme.error,
                dismissActionContentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            Snackbar(data)
        }
    }
}

/** Shows [message] and runs its action if the user taps it before it goes. */
suspend fun SnackbarHostState.show(message: UserMessage, text: String, actionLabel: String?) {
    val result = showSnackbar(AppSnackbarVisuals(text, actionLabel, message.isError))
    if (result == SnackbarResult.ActionPerformed) message.action?.run?.invoke()
}

package com.readfilestech.read_files_tech.ui.apps

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.apps.ApkArchives
import com.readfilestech.read_files_tech.data.apps.ApkInfo
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.text.withoutBidiControls
import com.readfilestech.read_files_tech.ui.common.IoDispatcher
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.EntryLaunching
import com.readfilestech.read_files_tech.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

data class ApkInstallUiState(
    val loading: Boolean = true,
    val info: ApkInfo? = null,
    val allowed: Boolean = false,
    /** The file described, as it was then; null when there is none to install. */
    val stamp: ApkStamp? = null,
)

@HiltViewModel
class ApkInstallViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val apks: ApkArchives,
    private val messages: UserMessages,
    val launching: EntryLaunching,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {
    /** As asked; null only if the route came without one (an outside app's deep link — refused). */
    private val requested: File? = savedState.get<String>(Routes.ARG_PATH)?.let(::File)
    val name: String = requested?.name.orEmpty()
    private val state = MutableStateFlow(ApkInstallUiState())
    val uiState: StateFlow<ApkInstallUiState> = state.asStateFlow()

    /**
     * On each return too: back from the settings, an access just allowed shows at once. The archive is
     * read by its REAL path, and its size and date kept: [install] refuses a file changed since. Not an app
     * rewriting it on purpose, size and date put back: it could as well have changed it before it was opened, and
     * the description is the archive's own words anyway (security audit of the 3.0, C1, refuted).
     */
    fun reload() {
        viewModelScope.launch {
            state.value = withContext(io) {
                val stamp = requested?.let { ApkStamp.of(realFile(it)) }
                ApkInstallUiState(
                    loading = false,
                    info = stamp?.let { apks.describe(it.file, ICON_PX) },
                    allowed = apks.canInstall(),
                    stamp = stamp,
                )
            }
        }
    }

    /**
     * The APK to the system's installer — if it is still the file described: replaced or rewritten
     * meanwhile (another app, a link retargeted), it is read and shown again instead (security review,
     * phase 3b; the installer shows its own confirmation too).
     */
    fun install(onReady: (File) -> Unit, onHandedOver: () -> Unit) {
        viewModelScope.launch {
            val shown = state.value.stamp ?: return@launch
            val now = withContext(io) { ApkStamp.of(realFile(shown.file)) }
            if (now == shown) {
                onReady(shown.file)
                onHandedOver()
            } else {
                messages.info(uiText(R.string.install_changed))
                reload()
            }
        }
    }

    fun openSettings(start: (Intent) -> Unit) {
        try {
            start(apks.installSettings())
        } catch (expected: ActivityNotFoundException) {
            messages.error(uiText(R.string.error_no_settings))
        }
    }

    private companion object {
        const val ICON_PX = 144

        @Suppress("SwallowedException") // unresolvable: the path as given, which the guard then judges
        fun realFile(file: File): File = try {
            file.canonicalFile
        } catch (expected: IOException) {
            file
        }
    }
}

/** A file as it was when described: where it really is, its size and its date. */
data class ApkStamp(val file: File, val length: Long, val modifiedMs: Long) {
    companion object {
        fun of(file: File) = ApkStamp(file, file.length(), file.lastModified())
    }
}

/**
 * Installing an APK the user tapped (`_installApk` of the 2.15.3): what it holds — its icon, name and
 * version, read from the archive — then the system's installer, through the app's FileProvider (an SD
 * card's APK too, which the 2.15.x could not serve, D34). Without "Install unknown apps" it says where to
 * allow it, and goes on when the user comes back: the 2.15.x left them to tap the file again.
 */
@Composable
fun ApkInstallDialog(onDismiss: () -> Unit, viewModel: ApkInstallViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.reload()
        onPauseOrDispose { }
    }
    val info = state.info
    Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = info?.icon
                if (icon != null) {
                    Image(icon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(48.dp))
                } else {
                    Icon(
                        Icons.Outlined.Android,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.size(16.dp))
                Column {
                    Text(info?.label ?: stringResource(R.string.feature_apk_install), style = MaterialTheme.typography.titleLarge)
                    info?.let {
                        Text(
                            versionLine(it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                info == null -> Text(stringResource(R.string.install_not_apk), style = MaterialTheme.typography.bodyMedium)
                !state.allowed -> Text(stringResource(R.string.install_permission), style = MaterialTheme.typography.bodyMedium)
                else -> Text(viewModel.name.withoutBidiControls(), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                when {
                    state.loading || info == null -> Unit
                    !state.allowed -> TextButton(onClick = { viewModel.openSettings(context::startActivity) }) {
                        Text(stringResource(R.string.action_open_settings))
                    }
                    else -> TextButton(onClick = {
                        viewModel.install(
                            onReady = { viewModel.launching.view(context, DirectoryListing.entryOf(it), chooser = false) },
                            onHandedOver = onDismiss,
                        )
                    }) { Text(stringResource(R.string.action_install)) }
                }
            }
        }
    }
}

@Composable
private fun versionLine(info: ApkInfo): String =
    info.versionName?.let { stringResource(R.string.install_version, it, info.packageName) } ?: info.packageName

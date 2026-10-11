package com.readfilestech.read_files_tech.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.storage.StorageAccess
import com.readfilestech.read_files_tech.data.storage.StorageModel
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.ExplorerScreen
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.search.SearchScreen
import com.readfilestech.read_files_tech.ui.trash.TrashScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The app's root: the one message host, storage access (asked once at the first launch, as the 2.15.x),
 * the shortcuts of the quick-settings tiles, and the destinations.
 *
 * @param shortcut the tile that opened the app, if any; [onShortcutHandled] clears it.
 */
@Composable
fun AppRoot(access: StorageAccess, messages: UserMessages, shortcut: StateFlow<Feature?>, onShortcutHandled: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val navController = rememberNavController()
    // The configuration's resources: a language changed while the app runs takes effect at once.
    val resources = LocalResources.current
    val settings = rememberStorageSettings(access, messages)

    LaunchedEffect(messages, resources) {
        // One at a time, in order: showSnackbar waits for the previous one to go.
        messages.messages.collect { message ->
            snackbar.show(message, message.text.resolve(resources), message.action?.label?.resolve(resources))
        }
    }
    LaunchedEffect(shortcut) {
        shortcut.collect { feature ->
            if (feature != null) {
                navController.navigate(Routes.pending(feature)) { launchSingleTop = true }
                onShortcutHandled()
            }
        }
    }

    CompositionLocalProvider(LocalAppSnackbar provides snackbar, LocalStorageSettings provides settings) {
        Destinations(navController)
        FirstLaunchWelcome(access, settings)
    }
}

@Composable
private fun Destinations(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.EXPLORER) {
        composable(
            Routes.EXPLORER,
            arguments = listOf(
                navArgument(Routes.ARG_PATH) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument(Routes.ARG_PICK) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val picked = entry.pickedFolder()
            ExplorerScreen(
                pickedFolder = picked.folder,
                onPickedFolderUsed = picked.consume,
                onOpenTrash = { navController.navigate(Routes.TRASH) { launchSingleTop = true } },
                onOpenSearch = { navController.navigate(Routes.SEARCH) { launchSingleTop = true } },
                onChooseFolder = { navController.navigate(Routes.explorer(it, pickFolder = true)) },
                onFolderChosen = { folder ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(Routes.RESULT_PICKED_FOLDER, folder)
                    navController.popBackStack()
                },
                onNavigate = { navController.navigate(it) },
                onLeave = { navController.popBackStack() },
            )
        }
        composable(Routes.TRASH) { TrashScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SEARCH) { entry ->
            val picked = entry.pickedFolder()
            SearchScreen(
                pickedRoot = picked.folder,
                onPickedRootUsed = picked.consume,
                onBack = { navController.popBackStack() },
                onChooseRoot = { navController.navigate(Routes.explorer(it, pickFolder = true)) },
                onOpenFolder = { navController.navigate(Routes.explorer(it)) },
            )
        }
        composable(Routes.PENDING, arguments = listOf(navArgument(Routes.ARG_FEATURE) { type = NavType.StringType })) { entry ->
            val feature = entry.arguments?.getString(Routes.ARG_FEATURE)?.let { name -> Feature.entries.find { it.name == name } }
            PendingScreen(feature, onBack = { navController.popBackStack() })
        }
    }
}

/** A folder handed back by the folder chooser, and how to mark it used so it is not handed twice. */
private class PickedFolder(val folder: String?, val consume: () -> Unit)

/**
 * What the chooser set on THIS entry's saved state ([Routes.RESULT_PICKED_FOLDER]). Read here, at the
 * navigation level: a view model's own SavedStateHandle is not the entry's, and never sees it.
 */
@Composable
private fun NavBackStackEntry.pickedFolder(): PickedFolder {
    val folder by savedStateHandle.getStateFlow<String?>(Routes.RESULT_PICKED_FOLDER, null).collectAsStateWithLifecycle()
    return PickedFolder(folder) { savedStateHandle[Routes.RESULT_PICKED_FOLDER] = null }
}

/**
 * The storage request. Android ≤ 10: the system dialog, then — refused, maybe for good — the app's
 * details, where the "Storage" permission is. Android 11+: the "All files access" page, with a message
 * saying what to switch on (the 2.15.x `_requestAllFilesAccess`).
 */
@Composable
private fun rememberStorageSettings(access: StorageAccess, messages: UserMessages): StorageSettings {
    val context = LocalContext.current
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { !it }) startFirst(context, listOf(access.appDetails()))
    }
    return remember(access) {
        StorageSettings(access.model) {
            when (access.model) {
                StorageModel.RUNTIME -> runtime.launch(StorageAccess.RUNTIME_PERMISSIONS)
                StorageModel.ALL_FILES -> if (startFirst(context, access.allFilesSettings())) {
                    messages.info(uiText(R.string.all_files_instructions))
                }
            }
        }
    }
}

/** Starts the first intent that has an activity; false when none has. */
private fun startFirst(context: Context, intents: List<Intent>): Boolean = intents.any { intent ->
    try {
        context.startActivity(intent)
        true
    } catch (expected: ActivityNotFoundException) {
        false
    }
}

/** The welcome of the first launch (`_requestStoragePermissions` of the 2.15.x), once in the app's life. */
@Composable
private fun FirstLaunchWelcome(access: StorageAccess, settings: StorageSettings) {
    var show by rememberSaveable { mutableStateOf(false) }
    var claimed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!claimed) {
            claimed = true
            show = withContext(Dispatchers.IO) { access.claimFirstRequest() }
        }
    }
    if (!show) return
    val allFiles = settings.model == StorageModel.ALL_FILES
    AlertDialog(
        onDismissRequest = { },
        icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
        title = { Text(stringResource(R.string.welcome_title)) },
        text = { Text(stringResource(if (allFiles) R.string.welcome_body_all_files else R.string.welcome_body_storage)) },
        dismissButton = { TextButton(onClick = { show = false }) { Text(stringResource(R.string.action_later)) } },
        confirmButton = {
            Button(onClick = {
                show = false
                settings.request()
            }) { Text(stringResource(if (allFiles) R.string.action_open_settings else R.string.action_allow)) }
        },
    )
}

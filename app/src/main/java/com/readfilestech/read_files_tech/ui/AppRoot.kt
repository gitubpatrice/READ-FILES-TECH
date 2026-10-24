package com.readfilestech.read_files_tech.ui

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.storage.StorageAccess
import com.readfilestech.read_files_tech.data.storage.StorageModel
import com.readfilestech.read_files_tech.ui.about.LegalDocument
import com.readfilestech.read_files_tech.ui.about.LegalScreen
import com.readfilestech.read_files_tech.ui.apps.ApkInstallDialog
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.common.startFirst
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.explorer.ExplorerScreen
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.home.HomeScreen
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.picker.PickerScreen
import com.readfilestech.read_files_tech.ui.search.SearchScreen
import com.readfilestech.read_files_tech.ui.trash.TrashScreen
import com.readfilestech.read_files_tech.ui.vault.VaultImportFolderScreen
import com.readfilestech.read_files_tech.ui.viewer.ViewerHost
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind
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

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(messages, resources, lifecycle) {
        // Only while the app is on screen: in the background a snackbar ran its timer unseen and the
        // result was lost (Claude review R5); queued, it waits for the user's return, and the one on
        // screen then comes back (R6). One at a time, in order: showSnackbar waits for it to go.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            messages.showEach { message ->
                snackbar.show(message, message.text.resolve(resources), message.action?.label?.resolve(resources))
            }
        }
    }
    LaunchedEffect(shortcut) {
        shortcut.collect { feature ->
            if (feature != null) {
                navController.navigate(Routes.feature(feature)) { launchSingleTop = true }
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
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { entry ->
            // "Open a file" (the picker), and the explorer tab's copy / move destination.
            val files = entry.picked<ArrayList<String>>(Routes.RESULT_PICKED_FILES)
            val folder = entry.picked<String>(Routes.RESULT_PICKED_FOLDER)
            HomeScreen(
                pickedFiles = files.value,
                onPickedFilesUsed = files.consume,
                pickedFolder = folder.value,
                onPickedFolderUsed = folder.consume,
                onNavigate = { navController.navigate(it) },
            )
        }
        composable(
            Routes.EXPLORER,
            arguments = listOf(Routes.ARG_PATH, Routes.ARG_PICK, Routes.ARG_EXTENSIONS, Routes.ARG_TITLE).map { name ->
                navArgument(name) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            },
        ) { entry ->
            val picked = entry.picked<String>(Routes.RESULT_PICKED_FOLDER)
            val startPicked = entry.picked<ArrayList<String>>(Routes.START_PICKED_FILES)
            val several = PickMode.of(entry.arguments?.getString(Routes.ARG_PICK)) == PickMode.FILES
            ExplorerScreen(
                pickedFolder = picked.value,
                onPickedFolderUsed = picked.consume,
                onOpenTrash = { navController.navigate(Routes.TRASH) { launchSingleTop = true } },
                onOpenSearch = { navController.navigate(Routes.SEARCH) { launchSingleTop = true } },
                onChooseFolder = { navController.navigate(Routes.explorer(it, pick = PickMode.FOLDER)) },
                onFolderChosen = { navController.handBack(entry, Routes.RESULT_PICKED_FOLDER, it) },
                onFilesChosen = {
                    if (several) {
                        navController.handBackPastPicker(entry, ArrayList(it))
                    } else {
                        navController.handBack(entry, Routes.RESULT_PICKED_FILES, ArrayList(it))
                    }
                },
                onNavigate = { navController.navigate(it) },
                onLeave = { navController.leave(entry) },
                startPicked = startPicked.value,
                onStartPickedUsed = startPicked.consume,
            )
        }
        composable(
            Routes.PICKER,
            arguments = listOf(Routes.ARG_PICK, Routes.ARG_EXTENSIONS, Routes.ARG_TITLE).map { name ->
                navArgument(name) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            },
        ) { entry ->
            // What the explorer chose for the picker, which hands it on to whoever asked.
            val folder = entry.picked<String>(Routes.RESULT_PICKED_FOLDER)
            val files = entry.picked<ArrayList<String>>(Routes.RESULT_PICKED_FILES)
            PickerScreen(
                pickedFiles = files.value,
                pickedFolder = folder.value,
                onResultUsed = {
                    folder.consume()
                    files.consume()
                },
                onBrowse = { route, chosen ->
                    navController.navigate(route)
                    // The choice so far goes with it, on the new entry (Routes.START_PICKED_FILES).
                    if (chosen.isNotEmpty()) {
                        navController.currentBackStackEntry
                            ?.takeIf { it.id != entry.id }
                            ?.savedStateHandle
                            ?.set(Routes.START_PICKED_FILES, ArrayList(chosen))
                    }
                },
                onNavigate = { navController.navigate(it) },
                onFilesPicked = { navController.handBack(entry, Routes.RESULT_PICKED_FILES, ArrayList(it)) },
                onFolderPicked = { navController.handBack(entry, Routes.RESULT_PICKED_FOLDER, it) },
                onCancel = { navController.leave(entry) },
            )
        }
        dialog(Routes.INSTALL, arguments = listOf(navArgument(Routes.ARG_PATH) { type = NavType.StringType })) { entry ->
            ApkInstallDialog(onDismiss = { navController.leave(entry) })
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(
                navArgument(Routes.ARG_VIEWER) { type = NavType.StringType },
            ) + listOf(Routes.ARG_PATH, Routes.ARG_GALLERY, Routes.ARG_VAULT).map { name ->
                navArgument(name) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            },
        ) { entry ->
            ViewerHost(
                ViewerKind.fromArgument(entry.arguments?.getString(Routes.ARG_VIEWER)),
                onBack = { navController.leave(entry) },
                onNavigate = { navController.navigate(it) },
            )
        }
        composable(Routes.TRASH) { entry -> TrashScreen(onBack = { navController.leave(entry) }) }
        composable(Routes.LEGAL, arguments = listOf(navArgument(Routes.ARG_DOCUMENT) { type = NavType.StringType })) { entry ->
            val name = entry.arguments?.getString(Routes.ARG_DOCUMENT)
            LegalScreen(LegalDocument.entries.find { it.name == name }, onBack = { navController.leave(entry) })
        }
        composable(
            Routes.VAULT_IMPORT,
            arguments = listOf(navArgument(Routes.ARG_PATH) { type = NavType.StringType }),
        ) { entry ->
            VaultImportFolderScreen(onDone = { navController.leave(entry) })
        }
        composable(Routes.SEARCH) { entry ->
            val picked = entry.picked<String>(Routes.RESULT_PICKED_FOLDER)
            SearchScreen(
                pickedRoot = picked.value,
                onPickedRootUsed = picked.consume,
                onBack = { navController.leave(entry) },
                onChooseRoot = { navController.navigate(Routes.explorer(it, pick = PickMode.FOLDER)) },
                onOpenFolder = { navController.navigate(Routes.explorer(it)) },
            )
        }
        composable(
            Routes.FEATURE,
            arguments = listOf(
                navArgument(Routes.ARG_FEATURE) { type = NavType.StringType },
                // The file a tool is opened on (the explorer's "Remove metadata"), read by its view model.
                navArgument(Routes.ARG_PATH) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val feature = entry.arguments?.getString(Routes.ARG_FEATURE)?.let { name -> Feature.entries.find { it.name == name } }
            val files = entry.picked<ArrayList<String>>(Routes.RESULT_PICKED_FILES)
            val folder = entry.picked<String>(Routes.RESULT_PICKED_FOLDER)
            val picked = PickedResults(files.value, folder.value) {
                files.consume()
                folder.consume()
            }
            FeatureHost(feature, picked, onNavigate = { navController.navigate(it) }, onBack = { navController.leave(entry) })
        }
    }
}

/** A result handed back by a chooser (a folder, files), and how to mark it used so it is not handed twice. */
private class Picked<T>(val value: T?, val consume: () -> Unit)

/**
 * What a chooser set on THIS entry's saved state under [key]. Read here, at the navigation level: a view
 * model's own SavedStateHandle is not the entry's, and never sees it.
 */
@Composable
private fun <T> NavBackStackEntry.picked(key: String): Picked<T> {
    val value by savedStateHandle.getStateFlow<T?>(key, null).collectAsStateWithLifecycle()
    return Picked(value) { savedStateHandle[key] = null }
}

/**
 * A chooser's result, set on the entry that asked, then back to it — only while [from] is still on top:
 * a second tap during the exit animation handed the result to nobody and popped the screen that asked,
 * the home itself when it was the one (Claude review R8).
 */
private fun NavHostController.handBack(from: NavBackStackEntry, key: String, value: Any) {
    if (currentBackStackEntry?.id != from.id) return
    previousBackStackEntry?.savedStateHandle?.set(key, value)
    popBackStack()
}

/**
 * The files an explorer chose for the picker that opened it, choosing several: the WHOLE choice — the explorer
 * started from the picker's ([Routes.START_PICKED_FILES]) —, handed at once to whoever asked the picker. Both
 * leave together, the picker is not shown again: it waited for a second "Confirm", and the first seemed only to
 * go back (seen on the S9). Not opened by a picker: handed back as any chooser's result.
 */
private fun NavHostController.handBackPastPicker(from: NavBackStackEntry, files: ArrayList<String>) {
    val picker = previousBackStackEntry
    if (currentBackStackEntry?.id != from.id || picker?.destination?.route != Routes.PICKER) {
        handBack(from, Routes.RESULT_PICKED_FILES, files)
        return
    }
    popBackStack()
    handBack(picker, Routes.RESULT_PICKED_FILES, files)
}

/** Leaves [from], if it is still the screen on top (the same double tap, on a back or a cancel button). */
private fun NavHostController.leave(from: NavBackStackEntry) {
    if (currentBackStackEntry?.id == from.id) popBackStack()
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

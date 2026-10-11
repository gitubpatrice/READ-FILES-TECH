package com.readfilestech.read_files_tech.data.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.readfilestech.read_files_tech.data.files.StorageVisibility
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.PrefRead
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** How this Android version lets an app read the whole of shared storage. */
enum class StorageModel {
    /** Android 11+: the "All files access" page of the system settings (`MANAGE_EXTERNAL_STORAGE`). */
    ALL_FILES,

    /**
     * Android 10 and older: a real runtime dialog (`READ/WRITE_EXTERNAL_STORAGE`), with
     * `requestLegacyExternalStorage` for Android 10. Sending these users to an "All files" page they do
     * not have was a dead end of the 2.15.x, found on the S9 on 2026-08-09 (`storage_access.dart`).
     */
    RUNTIME,
}

/**
 * Access to shared storage: `StorageAccess` of the 2.15.x. The version is READ here, never guessed —
 * the Dart side had to guess when its channel failed, and a wrong guess sent the user to the wrong
 * settings.
 */
@Singleton
class StorageAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val visibility: StorageVisibility,
    private val prefs: FlutterPreferences,
) {
    val model: StorageModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) StorageModel.ALL_FILES else StorageModel.RUNTIME

    /** The app can browse shared storage: all-files access on Android 11+, the storage permission below. */
    fun isGranted(): Boolean = visibility.seesEverything()

    /**
     * Whether to greet the user and ask for access: once in the app's life, under the 2.15.x key
     * `permissions_asked`, written BEFORE asking (`main.dart:44-45`) so a refusal is never asked again by
     * itself — the explorer's banner stays. A user the Flutter app already asked is not asked again; a
     * value that cannot be read is neither trusted nor rewritten (docs D-019).
     */
    fun claimFirstRequest(): Boolean = claimFirstRequest(prefs, ::isGranted)

    /**
     * The settings pages that grant all-files access, best first: this app's own page, the list of
     * every app (some Samsung builds lack the first), then the app's details.
     */
    fun allFilesSettings(): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri()))
            add(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        add(appDetails())
    }

    /** The app's details page, where Android 10 and older keep the "Storage" permission. */
    fun appDetails(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())

    private fun packageUri(): Uri = Uri.fromParts("package", context.packageName, null)

    companion object {
        /** The 2.15.x key (bool): the first request was made. */
        const val PERMISSIONS_ASKED = "permissions_asked"

        /** [StorageAccess.claimFirstRequest], on its preferences and the access of the moment. */
        internal fun claimFirstRequest(prefs: FlutterPreferences, granted: () -> Boolean): Boolean {
            val alreadyAsked = when (val asked = prefs.boolean(PERMISSIONS_ASKED)) {
                // `getBool(...) == true` in Dart: a stored false still asks.
                is PrefRead.Present -> asked.value
                is PrefRead.Unreadable -> true
                PrefRead.Absent -> false
            }
            if (alreadyAsked) return false
            prefs.commit { putBoolean(PERMISSIONS_ASKED, true) }
            return !granted()
        }

        /** What Android 10 and older ask in their dialog: read and write go together, in one group. */
        val RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }
}

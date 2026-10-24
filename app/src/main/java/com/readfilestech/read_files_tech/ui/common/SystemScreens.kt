package com.readfilestech.read_files_tech.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

/** Starts the first intent that has an activity; false when none has. */
internal fun startFirst(context: Context, intents: List<Intent>): Boolean = intents.any { intent ->
    try {
        context.startActivity(intent)
        true
    } catch (expected: ActivityNotFoundException) {
        false
    }
}

/**
 * Android's language page for this app (Android 13+), whose list AGP writes from the translated folders
 * (docs D-033). Not every manufacturer exposes it: the app's details page, one tap from the language, comes
 * next, as in App Manager Tech.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun languageSettings(context: Context): List<Intent> {
    val app = Uri.fromParts("package", context.packageName, null)
    return listOf(
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, app),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
    )
}

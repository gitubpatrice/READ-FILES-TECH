package com.readfilestech.read_files_tech.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What an APK file says about itself, as Android's installer would show it. */
data class ApkInfo(val label: String, val packageName: String, val versionName: String?, val icon: Bitmap?)

/**
 * APK files: what they hold (`getApkIcon` of the 2.15.x's native side), and whether this app may hand one
 * to the system's installer (`canInstallApks`, `openInstallPermissionSettings`).
 */
@Singleton
class ApkArchives @Inject constructor(@param:ApplicationContext private val context: Context) {
    /**
     * The app inside [file], its icon drawn at [iconPx]; null when Android cannot read it as an APK. Slow
     * (the archive is opened): off the main thread — the 2.15.x parsed every row's APK on it (F36).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun describe(file: File, iconPx: Int): ApkInfo? {
        val manager = context.packageManager
        // A damaged or hostile archive throws from the parser, of any type: it is "not an APK".
        return try {
            manager.getPackageArchiveInfo(file.path, 0)?.let { info ->
                info.applicationInfo?.let { application ->
                    // Read from the archive itself, not from an installed app of the same package.
                    application.sourceDir = file.path
                    application.publicSourceDir = file.path
                    ApkInfo(
                        label = application.loadLabel(manager).toString(),
                        packageName = info.packageName,
                        versionName = info.versionName,
                        icon = iconOf(application, manager, iconPx),
                    )
                }
            }
        } catch (expected: RuntimeException) {
            null
        }
    }

    /**
     * The app's icon at [iconPx], or null. Android decodes it at the size the archive declares: a hostile
     * APK can declare a gigantic one, and the OutOfMemoryError — an Error, past the catch above — ended
     * the app each time its folder was listed (security review, phase 3b). Only the icon is given up: the
     * huge allocation is the one that failed, the heap is free again.
     */
    @Suppress("SwallowedException")
    private fun iconOf(application: ApplicationInfo, manager: PackageManager, iconPx: Int): Bitmap? = try {
        application.loadIcon(manager)?.toBitmap(iconPx, iconPx)
    } catch (expected: OutOfMemoryError) {
        null
    }

    /** "Install unknown apps" allowed for this app (asked once, in the system's settings). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The settings page where the user allows it. */
    fun installSettings(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
}

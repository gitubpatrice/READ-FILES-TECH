package com.readfilestech.read_files_tech.startup

import android.app.Application
import android.os.Build
import java.io.File

/**
 * Which process of the app runs (docs D-029): the main one, or a worker that a hostile file may kill —
 * `:pdf`, where PdfBox reads. Only the main process clears and repairs at start (StartupCleanup) and
 * reads the preferences: SharedPreferences are not shared between processes, and a worker's purge of
 * the vault's decrypted copies would delete a file the main process is handing to another app.
 */
object AppProcess {
    /**
     * Whether [processName] is the main process of [packageName]: the package's own name, without a
     * `:suffix`. A name that could not be read is NOT taken for the main one — the main process then
     * skips one cleanup, which its next start does; a worker taken for the main one could do harm.
     */
    fun isMain(processName: String?, packageName: String): Boolean = processName == packageName

    /** This process's name: the system's from Android 9, `/proc/self/cmdline` before. */
    fun currentName(): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else commandLineName()

    private fun commandLineName(): String? = try {
        File("/proc/self/cmdline").readBytes().takeWhile {
            it != 0.toByte()
        }.toByteArray().toString(Charsets.UTF_8).ifEmpty { null }
    } catch (expected: java.io.IOException) {
        null
    }
}

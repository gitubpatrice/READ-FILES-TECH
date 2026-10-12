package com.readfilestech.read_files_tech.ui.explorer

import android.content.Context
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.sharing.PathGuard
import com.readfilestech.read_files_tech.ui.common.FileLauncher
import com.readfilestech.read_files_tech.ui.common.LaunchOutcome
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import javax.inject.Inject

/** The explorer's ways out to other apps, each saying why when it cannot. The activity is passed, never kept. */
class EntryLaunching @Inject constructor(
    private val launcher: FileLauncher,
    private val guard: PathGuard,
    private val messages: UserMessages,
) {
    /** Opens [entry] in another app; the chooser asks which ("Open with…"). */
    fun view(from: Context, entry: FileEntry, chooser: Boolean) = say(launcher.view(from, entry.file, chooser))

    /** The share sheet for the files among [entries]; folders are left out, as the 2.15.x did. */
    fun share(from: Context, entries: List<FileEntry>) {
        val files = entries.filterNot(FileEntry::isDirectory)
        if (files.isEmpty()) {
            messages.info(uiText(R.string.share_no_files))
            return
        }
        say(launcher.share(from, files.map(FileEntry::file)))
    }

    fun sendTo(from: Context, entry: FileEntry, packageName: String, appName: String) =
        say(launcher.sendTo(from, entry.file, packageName), appName)

    fun editInPdfTech(from: Context, entry: FileEntry) = say(launcher.editInPdfTech(from, entry.file), PDF_TECH_NAME)

    fun isInstalled(packageName: String): Boolean = launcher.isInstalled(packageName)

    /** Whether [entry] may be handed to another app at all: the guard each action goes through first. */
    fun canLeave(entry: FileEntry): Boolean = guard.resolve(entry.file) != null

    private fun say(outcome: LaunchOutcome, appName: String? = null) {
        FileLauncher.messageFor(outcome, appName)?.let(messages::post)
    }

    companion object {
        /** A product name, the same in every language. */
        const val PDF_TECH_NAME = "PDF Tech"
        const val KDRIVE_NAME = "kDrive"
        const val PROTON_DRIVE_NAME = "Proton Drive"
        const val GOOGLE_DRIVE_NAME = "Google Drive"
    }
}

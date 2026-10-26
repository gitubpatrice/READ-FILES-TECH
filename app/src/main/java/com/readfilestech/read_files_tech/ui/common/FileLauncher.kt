package com.readfilestech.read_files_tech.ui.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.FileNames
import com.readfilestech.read_files_tech.data.sharing.AppSignatures
import com.readfilestech.read_files_tech.data.sharing.KnownApps
import com.readfilestech.read_files_tech.data.sharing.MimeTypes
import com.readfilestech.read_files_tech.data.sharing.PathGuard
import com.readfilestech.read_files_tech.data.sharing.VaultShares
import com.readfilestech.read_files_tech.vault.VaultDirs
import com.readfilestech.read_files_tech.vault.VaultFiles
import com.readfilestech.read_files_tech.vault.VaultSession
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** How handing a file to another app went. */
enum class LaunchOutcome { STARTED, NO_APP, NOT_INSTALLED, REFUSED, FAILED }

/**
 * Hands the user's files to other apps — the `open_file` channel of the 2.15.x: open (with or without
 * the "Open with" chooser), share, send to one app, edit in PDF Tech.
 *
 * Every file goes through [PathGuard] first and is served by the app's FileProvider (authority
 * `<applicationId>.fileprovider`, unchanged, docs D-012) with a READ grant to the app the intent starts, which
 * Android keeps while that app's task lives — on the path, not on a copy: the privacy policy says so (security
 * audit of the 3.0, C2). The
 * share no longer copies anything: share_plus staged a plaintext copy of every shared file in
 * `cache/share_plus/`.
 *
 * Intents start from the calling activity, without NEW_TASK: "back" from the other app comes back here.
 */
@Singleton
class FileLauncher @Inject constructor(
    private val guard: PathGuard,
    private val signatures: AppSignatures,
    private val vaultDirs: VaultDirs,
    private val session: VaultSession,
) {
    fun view(from: Context, file: File, chooser: Boolean): LaunchOutcome {
        val (uri, mime) = served(from, file) ?: return LaunchOutcome.REFUSED
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(from, if (chooser) Intent.createChooser(view, from.getString(R.string.open_with_title)) else view)
    }

    /** The system share sheet, for one file or several (folders were left out by the caller). */
    fun share(from: Context, files: List<File>): LaunchOutcome {
        val served = files.mapNotNull { served(from, it) }
        // Every file may leave, or none goes; and nothing to share is no crash (clip() takes the first).
        if (served.isEmpty() || served.size != files.size) return LaunchOutcome.REFUSED
        val send = if (served.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, served.single().first)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(served.map { it.first }))
        }
        send.type = MimeTypes.common(served.map { it.second })
        // The grant travels with the chooser only through ClipData.
        send.clipData = clip(served.map { it.first })
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(from, Intent.createChooser(send, from.getString(R.string.share_title)))
    }

    /**
     * The share sheet for [copy], a vault file decrypted for this share (VaultFiles.copy for a share, docs
     * D-034): the one way out for the vault's plaintext, which PathGuard refuses everywhere else. Only a
     * file of a slot of `cache/vault_shared/`, resolved, links followed — anything else is refused.
     */
    fun shareVaultCopy(from: Context, copy: File): LaunchOutcome {
        // Locked since the copy was made: its purge may not have run yet — no URI is granted to it.
        val uri = VaultShares.resolve(copy, File(vaultDirs.cache, VaultFiles.SHARE_DIR))
            ?.takeIf { session.unlocked.value }
            ?.let { uriOf(from, it) }
            ?: return LaunchOutcome.REFUSED
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri).setType(mimeOf(copy.name))
        send.clipData = clip(listOf(uri))
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(from, Intent.createChooser(send, from.getString(R.string.share_title)))
    }

    /** `sendToPackage`: ACTION_SEND straight to one app (kDrive, Proton Drive), read-only. */
    fun sendTo(from: Context, file: File, packageName: String): LaunchOutcome {
        if (!signatures.isInstalled(packageName)) return LaunchOutcome.NOT_INSTALLED
        return served(from, file)?.let { (uri, mime) ->
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).setPackage(packageName)
            send.clipData = clip(listOf(uri))
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            start(from, send)
        } ?: LaunchOutcome.REFUSED
    }

    /**
     * "Edit in PDF Tech": the PDF opened in PDF Tech, which may write it back — but only if the app
     * installed under PDF Tech's name carries PDF Tech's certificate (plan, phase 3, finding 23). Any
     * other app under that name gets the file read-only, as every other app does.
     */
    fun editInPdfTech(from: Context, file: File): LaunchOutcome {
        if (!signatures.isInstalled(KnownApps.PDF_TECH)) return LaunchOutcome.NOT_INSTALLED
        return served(from, file)?.let { (uri, _) ->
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, MimeTypes.PDF).setPackage(KnownApps.PDF_TECH)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Write for a PDF only, as the 2.15.x (`mime == "application/pdf"`), whatever a caller offers.
            val pdf = FileNames.extension(file.name) == "pdf"
            if (pdf && signatures.isSignedBy(KnownApps.PDF_TECH, KnownApps.PDF_TECH_CERT_SHA256)) {
                view.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            start(from, view)
        } ?: LaunchOutcome.REFUSED
    }

    /** The guarded file's content URI and type, or null when it may not leave the app. */
    private fun served(from: Context, file: File): Pair<Uri, String>? =
        guard.resolve(file)?.let { resolved -> uriOf(from, resolved)?.let { it to mimeOf(resolved.name) } }

    private fun uriOf(from: Context, file: File): Uri? = try {
        FileProvider.getUriForFile(from, "${from.packageName}.fileprovider", file)
    } catch (expected: IllegalArgumentException) {
        // Under no root of file_paths.xml.
        null
    }

    private fun mimeOf(name: String): String {
        val extension = FileNames.extension(name)
        return MimeTypes.of(extension) ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: MimeTypes.ANY
    }

    private fun clip(uris: List<Uri>): ClipData {
        val clip = ClipData.newRawUri("", uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        return clip
    }

    // TooGenericExceptionCaught: startActivity reports an intent too large for the binder (thousands of
    // shared files) as a RuntimeException wrapping TransactionTooLargeException — it crashed the app
    // (Claude review R4). No suspending call here, so no cancellation to swallow.
    @Suppress("TooGenericExceptionCaught")
    private fun start(from: Context, intent: Intent): LaunchOutcome = try {
        from.startActivity(intent)
        LaunchOutcome.STARTED
    } catch (expected: ActivityNotFoundException) {
        LaunchOutcome.NO_APP
    } catch (expected: RuntimeException) {
        // SecurityException included.
        LaunchOutcome.FAILED
    }

    companion object {
        /** What to tell the user, or null when there is nothing to say (it started). */
        fun messageFor(outcome: LaunchOutcome, appName: String? = null): UserMessage? = when (outcome) {
            LaunchOutcome.STARTED -> null
            LaunchOutcome.NO_APP -> UserMessage(uiText(R.string.error_no_app), isError = true)
            LaunchOutcome.NOT_INSTALLED -> UserMessage(uiText(R.string.error_app_not_installed, appName.orEmpty()), isError = true)
            LaunchOutcome.REFUSED -> UserMessage(uiText(R.string.error_file_not_shareable), isError = true)
            LaunchOutcome.FAILED -> UserMessage(uiText(R.string.error_open_failed), isError = true)
        }
    }
}

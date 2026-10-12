package com.readfilestech.read_files_tech.check

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Base64
import com.readfilestech.read_files_tech.data.DartJson
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.recents.RecentFiles
import com.readfilestech.read_files_tech.data.trash.TrashStore
import com.readfilestech.read_files_tech.startup.StartupCleanup
import com.readfilestech.read_files_tech.vault.VaultBackups
import com.readfilestech.read_files_tech.vault.VaultFiles
import com.readfilestech.read_files_tech.vault.VaultLockoutStore
import com.readfilestech.read_files_tech.vault.VaultService
import com.readfilestech.read_files_tech.vault.ZeroKeyRepair
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import java.io.File

/** The singletons the check reads through: the very instances the app will use. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PortCheckEntryPoint {
    fun prefs(): FlutterPreferences

    fun recents(): RecentFiles

    fun trash(): TrashStore

    fun vault(): VaultService

    fun vaultFiles(): VaultFiles

    fun backups(): VaultBackups

    fun repair(): ZeroKeyRepair

    fun lockouts(): VaultLockoutStore

    fun startup(): StartupCleanup
}

/**
 * The phase 2 exit criterion (port plan, phase 2): installed over a Flutter 2.15.x that a seed filled,
 * the port reports everything it reads of that app's data, and a host script compares it with what the
 * Flutter app itself read (tools/phase2/). No screen exists before this criterion, hence this entry point.
 *
 * In the `debug` and `check` builds only (src/check), never in a release. `adb shell content call`, and
 * nobody else: the framework checks no permission for call(), so the caller's uid is checked here.
 *
 * ⚠️ TEST DATA ONLY (security review of 2026-10-11, S5-S6). The call CHANGES the data, as the app will:
 * it unlocks the vault (clearing its failure counter), runs the zero-key repair and restores backups
 * over the vault's files. Its report names files and carries the SHA-256 of each decrypted vault file,
 * which confirms a short or guessable content offline; the passwords travel in the command line of
 * the host. Never on a phone holding real data — the debug signature already keeps it off any phone
 * where the published app is installed.
 */
class PortCheckProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val uid = Binder.getCallingUid()
        if (uid != SHELL_UID && uid != ROOT_UID) throw SecurityException("uid $uid: adb shell only")
        if (method == METHOD_CORPUS) return corpus(requireNotNull(arg))
        require(method == METHOD_OBSERVE) { "unknown method $method" }
        val request = PortCheckRequest.decode(String(Base64.decode(requireNotNull(arg), Base64.NO_WRAP), Charsets.UTF_8))
        val app = requireNotNull(context).applicationContext
        val observation = PortObservation(dirsOf(app), EntryPointAccessors.fromApplication(app, PortCheckEntryPoint::class.java))
        val report = DartJson.encode(runBlocking { observation.observe(request) })
        val encoded = Base64.encodeToString(report.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return Bundle().apply { putString(KEY_REPORT, encoded) }
    }

    /** The hostile corpus of phase 4 (CorpusCheck), in the folder [dir] of the device. */
    private fun corpus(dir: String): Bundle {
        val app = requireNotNull(context).applicationContext
        val sandbox = requireNotNull(app.filesDir.parentFile)
        val report = DartJson.encode(runBlocking { CorpusCheck(sandbox).run(File(dir)) })
        return Bundle().apply {
            putString(KEY_REPORT, Base64.encodeToString(report.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        }
    }

    private fun dirsOf(app: Context): CheckDirs {
        val flutterDocs = app.getDir("flutter", Context.MODE_PRIVATE)
        val names = SharedStorageNames()
        return CheckDirs(
            data = requireNotNull(app.filesDir.parentFile),
            flutterDocs = flutterDocs,
            portHistory = File(app.filesDir, "history"),
            extractions = File(File("/storage/emulated/0", names.filesTechDir), "Extractions"),
        )
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor? =
        throw UnsupportedOperationException()

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int =
        throw UnsupportedOperationException()

    private companion object {
        /** `Process.SHELL_UID` (public from API 29 only) and `Process.ROOT_UID`. */
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
        const val METHOD_OBSERVE = "observe"
        const val METHOD_CORPUS = "corpus"
        const val KEY_REPORT = "report"
    }
}

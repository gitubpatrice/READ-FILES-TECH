package com.readfilestech.read_files_tech.check

import android.os.Build
import android.os.SystemClock
import com.readfilestech.read_files_tech.BuildConfig
import com.readfilestech.read_files_tech.data.DartJson
import com.readfilestech.read_files_tech.data.extractions.LegacyExtractions
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.prefs.PrefRead
import com.readfilestech.read_files_tech.data.recents.RecentFile
import com.readfilestech.read_files_tech.data.recents.RecentFiles
import java.io.File

/**
 * Everything of the Flutter app's data the port reads, in the order the app will meet it, as one JSON
 * object (DartJson). The vault comes last but one: unlocking clears the counter that the lockout section
 * has just reported, and a restore replaces files. The sandbox is listed once the startup cleanup is done,
 * and at the end.
 */
class PortObservation(private val dirs: CheckDirs, private val deps: PortCheckEntryPoint) {
    suspend fun observe(request: PortCheckRequest): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["port"] = port()
        // What RftApplication started when this process was created: the sandbox is listed after it.
        out["startup"] = attempt {
            deps.startup().await()
            "done"
        }
        out["sandboxAtStart"] = attempt { Sandbox.list(dirs.data) }
        out["prefsBefore"] = attempt { PrefsSnapshot.of(deps.prefs()) }
        out["recents"] = attempt { recents() }
        out["trash"] = attempt { trash() }
        out["history"] = attempt { history(request.historyNames) }
        out["extractions"] = attempt { extractions() }
        out["vault"] = attempt { VaultObservation(deps).observe(request) }
        out["prefsAfter"] = attempt { PrefsSnapshot.of(deps.prefs()) }
        out["sandboxAfter"] = attempt { Sandbox.list(dirs.data) }
        return out
    }

    private fun port() = linkedMapOf(
        "applicationId" to BuildConfig.APPLICATION_ID,
        "buildType" to BuildConfig.BUILD_TYPE,
        "versionName" to BuildConfig.VERSION_NAME,
        "sdk" to Build.VERSION.SDK_INT,
        "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
        "bootId" to attempt { File(BOOT_ID).readText().trim() },
        "wallMs" to System.currentTimeMillis(),
        "monoMs" to SystemClock.elapsedRealtime(),
    )

    /**
     * The stored list as the core's `RecentFile.fromJsonString` reads each entry, re-encoded by
     * `toJson()` — equal to what Dart re-encodes only if both read the same values — then what the app
     * shows (`load()`), and the dismissed paths.
     */
    private fun recents(): Map<String, Any?> {
        val stored: Any? = when (val read = deps.prefs().stringList(RecentFiles.KEY)) {
            is PrefRead.Present -> read.value.map(::reEncoded)
            is PrefRead.Unreadable -> linkedMapOf("unreadable" to read.reason)
            PrefRead.Absent -> null
        }
        return linkedMapOf(
            "stored" to stored,
            "loaded" to deps.recents().load().map { it.path },
            "dismissed" to deps.recents().dismissed().sorted(),
        )
    }

    /** One stored entry: re-encoded as the core does, or kept as it is when the port cannot read it. */
    private fun reEncoded(raw: String): Any? {
        val entry = RecentFile.fromJson(raw) ?: return mapOf("unreadable" to raw)
        return DartJson.decode(entry.toJson())
    }

    private fun trash(): List<Map<String, Any?>> = deps.trash().list().map { entry ->
        linkedMapOf(
            "id" to entry.id,
            "trashRoot" to entry.trashRoot.path,
            "originalPath" to entry.originalPath,
            "name" to entry.name,
            "isDir" to entry.isDir,
            "size" to entry.size,
            "deletedAt" to entry.deletedAt.toIso8601String(),
            "payload" to Digests.tree(entry.payload),
        )
    }

    private fun history(names: List<String>): Map<String, Any?> {
        val history = EditorHistory(File(dirs.flutterDocs, HISTORY_DIR), dirs.portHistory)
        val legacy = history.legacyAll().sortedBy { it.file.name }.map { copy ->
            linkedMapOf("name" to copy.file.name, "savedAtMs" to copy.savedAtMs, "sha256" to Digests.sha256(copy.file))
        }
        return linkedMapOf(
            "legacy" to legacy,
            "byName" to names.associateWith { name -> history.legacyFor(name).map { it.file.name } },
        )
    }

    private fun extractions(): List<Map<String, Any?>> = LegacyExtractions(dirs.flutterDocs, dirs.extractions).find().map { folder ->
        linkedMapOf(
            "dir" to folder.dir.name,
            "archiveName" to folder.archiveName,
            "files" to folder.files,
            "bytes" to folder.bytes,
            "content" to Digests.tree(folder.dir),
        )
    }

    private companion object {
        const val HISTORY_DIR = "history"

        /** Changes at every boot: the host tells a check made after a reboot (D2) from one made before. */
        const val BOOT_ID = "/proc/sys/kernel/random/boot_id"
    }
}

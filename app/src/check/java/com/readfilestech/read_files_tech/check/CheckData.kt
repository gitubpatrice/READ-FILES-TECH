package com.readfilestech.read_files_tech.check

import com.readfilestech.read_files_tech.data.DartJson
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.PrefRead
import com.readfilestech.read_files_tech.vault.BackupTooCostlyException
import com.readfilestech.read_files_tech.vault.LockedDuringOperationException
import com.readfilestech.read_files_tech.vault.NameRefusedException
import com.readfilestech.read_files_tech.vault.NameTakenException
import com.readfilestech.read_files_tech.vault.TooLargeException
import com.readfilestech.read_files_tech.vault.VaultCorruptedException
import com.readfilestech.read_files_tech.vault.VaultLockedException
import com.readfilestech.read_files_tech.vault.VaultLockoutException
import com.readfilestech.read_files_tech.vault.VaultStorageException
import com.readfilestech.read_files_tech.vault.format.VaultAuthenticationException
import com.readfilestech.read_files_tech.vault.format.VaultFormatException
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** What the host asks for: JSON, passed base64-encoded in `content call --arg`. */
class PortCheckRequest(
    val vaultPassword: String?,
    val backupPassword: String?,
    /** Run the one-off zero-key repair (docs D-016) after the unlock, as the app will. */
    val repair: Boolean,
    /** `.rftvault` files to restore, each over the vault (existing names replaced), then read back. */
    val restores: List<String>,
    /** File names the 2.15.x editor saved copies of: the old copies of EACH are listed (docs D-021). */
    val historyNames: List<String>,
    /** The report written to a file on the device instead of returned (PortCheckProvider.reportToFile). */
    val reportToFile: Boolean = false,
) {
    companion object {
        fun decode(json: String): PortCheckRequest {
            val map = requireNotNull(DartJson.decode(json) as? Map<*, *>) { "the request is not a JSON object" }
            return PortCheckRequest(
                vaultPassword = map["vaultPassword"] as? String,
                backupPassword = map["backupPassword"] as? String,
                repair = map["repair"] == true,
                restores = strings(map["restores"]),
                historyNames = strings(map["historyNames"]),
                reportToFile = map["reportToFile"] == true,
            )
        }

        private fun strings(value: Any?): List<String> = (value as? List<*>).orEmpty().map { it as String }
    }
}

/** The directories the check reads, from the app's Context (PortCheckProvider) or a test. */
class CheckDirs(
    /** `/data/user/0/<package>`. */
    val data: File,
    /** `app_flutter/`, path_provider's documents of the 2.15.x. */
    val flutterDocs: File,
    /** Where the port keeps its own editor history (docs D-021). */
    val portHistory: File,
    /** `Files Tech/Extractions/` on shared storage (docs D-022). */
    val extractions: File,
)

/**
 * A failure, as data: the report goes on after it. Codes and not class names, which R8 renames in the
 * `check` build.
 */
fun failure(e: Throwable): Map<String, Any?> {
    val code = FAILURE_CODES.firstOrNull { it.first.isInstance(e) }?.second ?: "other"
    return linkedMapOf("error" to code, "message" to e.message, "remainingMs" to (e as? VaultLockoutException)?.remainingMs)
}

/** In order: a subclass before its parent (LockedDuringOperationException is a VaultLockedException). */
private val FAILURE_CODES = listOf(
    VaultLockoutException::class to "lockout",
    LockedDuringOperationException::class to "lockedDuringOperation",
    VaultLockedException::class to "locked",
    VaultCorruptedException::class to "corrupted",
    VaultStorageException::class to "storage",
    BackupTooCostlyException::class to "tooCostly",
    TooLargeException::class to "tooLarge",
    NameRefusedException::class to "nameRefused",
    NameTakenException::class to "nameTaken",
    VaultAuthenticationException::class to "authentication",
    VaultFormatException::class to "format",
    OutOfMemoryError::class to "outOfMemory",
    IOException::class to "io",
)

/**
 * [block]'s value, or its failure as data. An OutOfMemoryError is reported too: the small-heap devices
 * are part of the criterion (docs D-023), and a crash would report nothing.
 */
@Suppress("TooGenericExceptionCaught") // a check reports every failure; cancellation is rethrown
inline fun attempt(block: () -> Any?): Any? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    failure(e)
} catch (e: OutOfMemoryError) {
    failure(e)
}

object Digests {
    private const val BUFFER = 64 * 1024

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * A file: its size and SHA-256. A folder: each regular file under it by its path relative to it
     * (`/`-separated, sorted). A symbolic link is never followed.
     */
    fun tree(root: File): Map<String, Any?> = when {
        Files.isSymbolicLink(root.toPath()) -> linkedMapOf("type" to "link")
        root.isFile -> linkedMapOf("type" to "file", "size" to root.length(), "sha256" to sha256(root))
        root.isDirectory -> linkedMapOf("type" to "dir", "files" to filesUnder(root))
        else -> linkedMapOf("type" to "absent")
    }

    private fun filesUnder(root: File): Map<String, String> = root.walkTopDown()
        .onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }
        .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }
        .map { it.relativeTo(root).invariantSeparatorsPath to sha256(it) }
        .sortedBy { it.first }
        .toMap(LinkedHashMap())
}

/** The app's sandbox as `find` would list it: path relative to it, type, size. Links are not followed. */
object Sandbox {
    fun list(root: File): List<Map<String, Any?>> = root.walkTopDown()
        .onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }
        .filter { it != root }
        .map { entry ->
            val type = when {
                Files.isSymbolicLink(entry.toPath()) -> "l"
                entry.isDirectory -> "d"
                else -> "f"
            }
            linkedMapOf("path" to entry.relativeTo(root).invariantSeparatorsPath, "type" to type, "size" to entry.length())
        }
        .sortedBy { it["path"] as String }
        .toList()
}

/**
 * Every Dart key of the Flutter preferences file, as the port reads it: its type and value, or why it
 * cannot be read (docs D-019). A list is tried before a string, as its stored form is a prefixed string.
 * Keys without the `flutter.` prefix are not Dart's: the host's census lists them from the raw file.
 */
object PrefsSnapshot {
    fun of(prefs: FlutterPreferences): Map<String, Any?> = prefs.keys().sorted().associateWith { read(prefs, it) }

    private fun read(prefs: FlutterPreferences, key: String): Map<String, Any?> {
        val attempts = listOf(
            "bool" to prefs.boolean(key),
            "int" to prefs.long(key),
            "List<String>" to prefs.stringList(key),
            "String" to prefs.string(key),
        )
        val found = attempts.firstOrNull { it.second is PrefRead.Present }
        return if (found != null) {
            linkedMapOf("type" to found.first, "value" to (found.second as PrefRead.Present).value)
        } else {
            linkedMapOf("type" to "unreadable", "reasons" to attempts.map { (it.second as? PrefRead.Unreadable)?.reason })
        }
    }
}

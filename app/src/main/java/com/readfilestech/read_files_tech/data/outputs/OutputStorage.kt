package com.readfilestech.read_files_tech.data.outputs

import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileMoves
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.PrefRead
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The subfolders of the output folder, named on disk as the 2.15.x names them (`OutputCategory`). */
enum class OutputCategory(val folderName: String) {
    SCANS("Scans"),
    CONVERSIONS("Conversions"),
    COMPRESSIONS("Compressions"),
    SIGNATURES("Signatures"),
    NO_EXIF("Sans-EXIF"),
    OCR("OCR"),
    VAULT_BACKUPS("Sauvegardes coffre"),

    /** "Extract all" of the ZIP viewer: one folder per extraction (docs D-022). */
    EXTRACTIONS("Extractions"),
}

/** No folder could take an output: neither the configured one nor either fallback. */
class NoOutputLocationException(cause: Throwable?) : IOException("no writable location for an output", cause)

/**
 * Where the tools put what they make — `OutputStorageService` of the 2.15.3, on the same preference and
 * folders: `<base>/<category>/<name>_<yyyyMMdd_HHmmss>.<ext>`, the base tried first, then the app's own
 * folder on shared storage, then its private one.
 *
 * Two departures, both from the inventory: only a name ALREADY TAKEN moves on to the next candidate —
 * the 2.15.x took a refused write for one, and tried 1 000 names before falling back (D49) — and every
 * fallback is listed by [bases], so the picker finds what landed there (D46).
 *
 * @param primary the user's primary volume (`/storage/emulated/0` for the owner).
 * @param appExternal the app's own folder on shared storage (`Android/data/<pkg>/files`), null when unmounted.
 * @param appPrivate `app_flutter/`, the 2.15.x's last resort (path_provider's documents).
 * @param folder the outputs' folder under each of the three: `Files Tech`, suffixed in a `.next` build so
 *   it never writes into the real app's folder (docs D-017) — the module passes [SharedStorageNames.filesTechDir].
 */
class OutputStorage(
    private val prefs: FlutterPreferences,
    private val primary: () -> File,
    private val appExternal: () -> File?,
    private val appPrivate: File,
    private val folder: String = SharedStorageNames.FLUTTER_FILES_TECH_DIR,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    /** The configured base (`output_base_path`, verbatim, as the 2.15.x reads it), or `<primary>/<folder>`. */
    fun basePath(): File =
        (prefs.string(KEY_BASE_PATH) as? PrefRead.Present)?.value?.takeIf(String::isNotEmpty)?.let(::File) ?: File(primary(), folder)

    /** "Share automatically after creation" (`output_auto_share`), off by default. */
    fun autoShare(): Boolean = (prefs.boolean(KEY_AUTO_SHARE) as? PrefRead.Present)?.value ?: false

    /** Every folder outputs may be in, in the order they are tried: the base, then the two fallbacks. */
    fun bases(): List<File> = listOfNotNull(basePath(), appExternal()?.let { File(it, folder) }, File(appPrivate, folder))

    /**
     * A new, empty file reserved for an output — created EXCLUSIVELY, so two tools never share one and
     * nothing is replaced: `name.ext`, then `name_1.ext` … `name_999.ext`. Fill it with [write]; on a
     * failure before that, give it back with [discard].
     *
     * @throws NoOutputLocationException no folder could take it.
     */
    fun reserve(category: OutputCategory, suggestedName: String, extension: String): File {
        val name = "${sanitize(suggestedName)}_${clock().format(STAMP)}"
        val ext = sanitize(extension)
        var failure: IOException? = null
        for (base in bases()) {
            val dir = File(base, category.folderName)
            try {
                Files.createDirectories(dir.toPath())
                return reserveIn(dir, name, ext)
            } catch (e: IOException) {
                // This folder cannot take it (refused, read-only, full, no free name): the next one.
                failure = e
            }
        }
        throw NoOutputLocationException(failure)
    }

    /**
     * The folder an output of [category] goes to now: the first of [bases] that takes it. For a writer
     * that makes its temporary file there and reserves the output only once it is written, with
     * [reserveAt] — the vault's backup: a lock during its export must leave nothing in `Files Tech/`
     * (2.15.3, `exportToBackup`).
     *
     * @throws NoOutputLocationException no folder could take it.
     */
    fun folderFor(category: OutputCategory): File {
        var failure: IOException? = null
        for (base in bases()) {
            val dir = File(base, category.folderName)
            try {
                Files.createDirectories(dir.toPath())
                return dir
            } catch (e: IOException) {
                failure = e
            }
        }
        throw NoOutputLocationException(failure)
    }

    /** A new, empty file reserved as [reserve] does, but in [folder] — the one [folderFor] gave. */
    fun reserveAt(folder: File, suggestedName: String, extension: String): File =
        reserveIn(folder, "${sanitize(suggestedName)}_${clock().format(STAMP)}", sanitize(extension))

    /**
     * Writes the output into its [reservation], atomically: a temporary file beside it, synced, then
     * moved over the reservation in one step — and only while the reservation is still the empty file
     * reserved: what took its place during the write is left alone. One instant remains, between that
     * check and the rename, which no rename on Android can close (the one that refuses to replace would
     * refuse the reservation itself) — the window accepted for copies too (Claude reviews R6, R8).
     * Writing into the reservation in place would close it, and leave a truncated file under the real
     * name when the app dies half-way (review R4).
     */
    fun write(reservation: File, write: (OutputStream) -> Unit) {
        AtomicFiles.write(reservation, publish = { from, to ->
            if (!FileMoves.isFreeOrEmpty(to)) throw FileAlreadyExistsException(to.path)
            AtomicFiles.publish(from, to)
        }, write = write)
    }

    /** A reservation never filled: removed if still empty (the 2.15.x left it, D18). */
    fun discard(reservation: File) = FileMoves.discardReservation(reservation)

    /**
     * A new, empty FOLDER for an output made of several files — `<base>/<category>/<name>_<stamp>/`,
     * named as [reserve] names a file and created exclusively the same way, never an existing folder
     * (the 2.15.x's "Extract all" wrote into the folder of the previous extraction, D17). The app's
     * private fallback is not tried: files no other app nor the explorer can reach are what D-022 moves
     * OUT of `app_flutter/`.
     *
     * @throws NoOutputLocationException no folder of shared storage could take it.
     */
    fun reserveFolder(category: OutputCategory, suggestedName: String): File {
        val name = "${sanitize(suggestedName)}_${clock().format(STAMP)}"
        var failure: IOException? = null
        for (base in listOfNotNull(basePath(), appExternal()?.let { File(it, folder) })) {
            val dir = File(base, category.folderName)
            try {
                Files.createDirectories(dir.toPath())
                return reserveIn(dir, name, ext = null)
            } catch (e: IOException) {
                failure = e
            }
        }
        throw NoOutputLocationException(failure)
    }

    /** `name.ext` (a folder: `name`), then `_1` … `_999` before the extension, created exclusively. */
    private fun reserveIn(dir: File, name: String, ext: String?): File {
        for (i in 0 until MAX_CANDIDATES) {
            val stem = if (i == 0) name else "${name}_$i"
            val candidate = File(dir, if (ext == null) stem else "$stem.$ext")
            try {
                if (ext == null) Files.createDirectory(candidate.toPath()) else Files.createFile(candidate.toPath())
                return candidate
            } catch (expected: FileAlreadyExistsException) {
                // Taken: the next candidate. Any OTHER error is this folder's, and goes up (D49).
            }
        }
        throw IOException("no free name in $dir for $name")
    }

    companion object {
        const val KEY_BASE_PATH = "output_base_path"
        const val KEY_AUTO_SHARE = "output_auto_share"

        private const val MAX_CANDIDATES = 1_000
        private const val MAX_NAME_CHARS = 60
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        private val CONTROL = Regex("[\\u0000-\\u001f]")
        private val RESERVED = Regex("[/\\\\:*?\"<>|]")

        /** Dart's `\s`, which is ECMAScript's — Unicode spaces included — not Java's ASCII one. */
        private val SPACES = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")
        private val UNDERSCORES = Regex("_+")
        private val EDGES = Regex("^[._]+|[._]+$")

        /**
         * `FormatUtils.sanitizeForFs` of the core (ef100dd): controls and `/\:*?"<>|` to `_`, spaces to
         * `_`, runs of `_` collapsed, `.` and `_` trimmed at both ends, `fichier` if nothing is left, 60
         * characters at most — never cutting a surrogate pair in two, which Dart's `substring` did.
         */
        fun sanitize(name: String): String {
            val cleaned = name.replace(CONTROL, "_").replace(RESERVED, "_").replace(SPACES, "_")
                .replace(UNDERSCORES, "_").replace(EDGES, "").ifEmpty { "fichier" }
            if (cleaned.length <= MAX_NAME_CHARS) return cleaned
            val end = if (Character.isHighSurrogate(cleaned[MAX_NAME_CHARS - 1])) MAX_NAME_CHARS - 1 else MAX_NAME_CHARS
            return cleaned.substring(0, end)
        }
    }
}

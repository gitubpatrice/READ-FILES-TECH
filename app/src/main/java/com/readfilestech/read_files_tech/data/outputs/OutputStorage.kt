package com.readfilestech.read_files_tech.data.outputs

import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileMoves
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
 */
class OutputStorage(
    private val prefs: FlutterPreferences,
    private val primary: () -> File,
    private val appExternal: () -> File?,
    private val appPrivate: File,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    /** The configured base (`output_base_path`, verbatim, as the 2.15.x reads it), or `<primary>/Files Tech`. */
    fun basePath(): File =
        (prefs.string(KEY_BASE_PATH) as? PrefRead.Present)?.value?.takeIf(String::isNotEmpty)?.let(::File) ?: File(primary(), FOLDER)

    /** "Share automatically after creation" (`output_auto_share`), off by default. */
    fun autoShare(): Boolean = (prefs.boolean(KEY_AUTO_SHARE) as? PrefRead.Present)?.value ?: false

    /** Every folder outputs may be in, in the order they are tried: the base, then the two fallbacks. */
    fun bases(): List<File> = listOfNotNull(basePath(), appExternal()?.let { File(it, FOLDER) }, File(appPrivate, FOLDER))

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
     * Writes the output into its [reservation], atomically: a temporary file beside it, synced, then
     * moved over the reservation in one step — and only while the reservation is still the empty file
     * reserved: what took its place meanwhile is left alone.
     */
    fun write(reservation: File, write: (OutputStream) -> Unit) {
        AtomicFiles.write(reservation, publish = { from, to ->
            if (!FileMoves.isFreeOrEmpty(to)) throw FileAlreadyExistsException(to.path)
            AtomicFiles.publish(from, to)
        }, write = write)
    }

    /** A reservation never filled: removed if still empty (the 2.15.x left it, D18). */
    fun discard(reservation: File) = FileMoves.discardReservation(reservation)

    private fun reserveIn(dir: File, name: String, ext: String): File {
        for (i in 0 until MAX_CANDIDATES) {
            val candidate = File(dir, if (i == 0) "$name.$ext" else "${name}_$i.$ext")
            try {
                Files.createFile(candidate.toPath())
                return candidate
            } catch (expected: FileAlreadyExistsException) {
                // Taken: the next candidate. Any OTHER error is this folder's, and goes up (D49).
            }
        }
        throw IOException("no free name in $dir for $name.$ext")
    }

    companion object {
        const val KEY_BASE_PATH = "output_base_path"
        const val KEY_AUTO_SHARE = "output_auto_share"

        /** The folder under the primary volume, and under each fallback. */
        const val FOLDER = "Files Tech"

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

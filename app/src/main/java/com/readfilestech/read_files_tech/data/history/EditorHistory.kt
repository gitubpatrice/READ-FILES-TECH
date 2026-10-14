package com.readfilestech.read_files_tech.data.history

import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.files.FileTrees
import java.io.File
import java.security.MessageDigest

/**
 * One saved version: the raw copy of a file BEFORE it was overwritten, and when that was — [savedAtMs], read
 * from its name, which orders the copies.
 */
data class HistoryCopy(val file: File, val savedAtMs: Long) {
    val size: Long get() = file.length()

    /**
     * When the copy was written, to show: once the clock went ahead and back, every later name follows the
     * newest, a millisecond apart, all dated ahead (Claude review R21). The name where the file gives no date.
     */
    val writtenAtMs: Long get() = file.lastModified().takeIf { it > 0 } ?: savedAtMs
}

/**
 * The code editor's history (docs D-021).
 *
 * The 2.15.x kept `app_flutter/history/<name>_<epochMs>.bak` — `<name>` the file NAME with every
 * UTF-16 unit outside `[A-Za-z0-9_.]` made `_`, ten per name, without the original path. Its filter
 * `contains('<name>_')` mixed `mes_notes.txt` with `notes.txt`, and the rotation of one deleted the
 * copies of the other (D6). So:
 * - the NEW history is kept per full PATH, in a folder of its own ([newHistoryDir]);
 * - the old copies are never attached to a file automatically (their file is unknown), nor deleted
 *   in silence (one may be the only copy of an older version): [legacyFor] lists those of the EXACT
 *   name, to show apart, and the new history's rotation never touches them.
 */
class EditorHistory(
    /** `app_flutter/history/` of the 2.15.x. */
    private val legacyDir: File,
    /** Where the port keeps its own history, one folder per path. */
    private val newHistoryDir: File,
    /** How a copy is written ([AtomicFiles.writeBytes]); a test makes it fail. */
    private val writeCopy: (File, ByteArray) -> Unit = AtomicFiles::writeBytes,
) {
    /** The old copies whose name is EXACTLY the one the 2.15.x derived from [fileName], newest first. */
    fun legacyFor(fileName: String): List<HistoryCopy> {
        val pattern = Regex("^" + Regex.escape(legacyBase(fileName)) + """_(\d+)\.bak$""")
        return legacyDir.listFiles().orEmpty()
            .filter { FileTrees.isRegularFile(it) }
            .mapNotNull { file -> pattern.matchEntire(file.name)?.let { HistoryCopy(file, it.groupValues[1].toLong()) } }
            .sortedByDescending { it.savedAtMs }
    }

    /** All the old copies: their number and total size, for "Delete the old history (N files, X KB)". */
    fun legacyAll(): List<HistoryCopy> = legacyDir.listFiles().orEmpty()
        .filter { FileTrees.isRegularFile(it) }
        .mapNotNull { file -> LEGACY_NAME.matchEntire(file.name)?.let { HistoryCopy(file, it.groupValues[1].toLong()) } }

    /** Deletes every old copy (settings, panic, reset): the whole `history/` of the 2.15.x. */
    fun deleteLegacy(): Boolean = FileTrees.delete(legacyDir)

    /**
     * Saves [current], the content of [path] about to be overwritten, then keeps the last [KEPT] of THAT
     * path. A copy never replaces another: two saves in the same millisecond take the next free one,
     * reserved by an exclusive creation (GPT review R3).
     *
     * Named after the newest copy when the clock went back (Claude review R20): named from [nowMs] alone,
     * it sorted below the ten newest and the rotation deleted it at once. A copy that fails to be written
     * leaves no empty name behind, which would count as a version and push a real one out (R20).
     */
    @Synchronized
    fun saveBefore(path: String, current: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        val dir = folderOf(path)
        dir.mkdirs()
        AtomicFiles.writeBytes(File(dir, PATH_FILE), path.toByteArray(Charsets.UTF_8))
        val first = maxOf(nowMs, (versions(path).firstOrNull()?.savedAtMs ?: -1) + 1)
        val copy = generateSequence(first) { it + 1 }.map { File(dir, "$it$BAK") }.first { it.createNewFile() }
        var written = false
        try {
            writeCopy(copy, current)
            written = true
            copy.setLastModified(nowMs)
        } finally {
            if (!written) copy.delete()
        }
        versions(path).drop(KEPT).forEach { it.file.delete() }
    }

    /** The saved versions of [path], newest first. */
    fun versions(path: String): List<HistoryCopy> = folderOf(path).listFiles().orEmpty()
        .filter { FileTrees.isRegularFile(it) && NEW_NAME.matches(it.name) }
        .map { HistoryCopy(it, it.name.removeSuffix(BAK).toLong()) }
        .sortedByDescending { it.savedAtMs }

    /** Deletes the port's own history (panic, reset). */
    fun deleteNew(): Boolean = FileTrees.delete(newHistoryDir)

    private fun folderOf(path: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return File(newHistoryDir, digest.joinToString("") { "%02x".format(it) })
    }

    companion object {
        /** Copies kept per file, as in the 2.15.x. */
        const val KEPT = 10
        private const val BAK = ".bak"
        private const val PATH_FILE = "path"
        private val LEGACY_NAME = Regex("""^.*_(\d+)\.bak$""")
        private val NEW_NAME = Regex("""^\d+\.bak$""")

        /**
         * `name.replaceAll(RegExp(r'[^\w.]'), '_')` of the 2.15.x. Dart's `\w` is ASCII, and its RegExp
         * works on UTF-16 units: an emoji (two units) became `__`. A Java regex would see one code point
         * and write `_` once — another name, and the old copies would not be found. Unit by unit, then.
         */
        fun legacyBase(fileName: String): String = fileName.map { c -> if (isDartWordOrDot(c)) c else '_' }.joinToString("")

        /** `[\w.]` of a Dart RegExp without the unicode flag: ASCII letters, digits, `_`, and `.`. */
        private fun isDartWordOrDot(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "_."
    }
}

package com.readfilestech.read_files_tech.data.trash

import com.readfilestech.read_files_tech.data.DartDateTime
import com.readfilestech.read_files_tech.data.DartJson
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * One entry of the trash: the payload moved to `<root>/items/<id>/<name>` and its metadata
 * `<root>/meta/<id>.json`. Port of `TrashEntry` (`lib/services/trash_service.dart`, 2.15.3): same
 * fields, same JSON, same validation.
 */
data class TrashEntry(
    val id: String,
    val trashRoot: File,
    val originalPath: String,
    val name: String,
    val isDir: Boolean,
    /** In bytes; -1 when unknown (directories). */
    val size: Long,
    val deletedAt: DartDateTime,
) {
    val itemDir: File get() = File(File(trashRoot, ITEMS_DIR), id)
    val payload: File get() = File(itemDir, name)
    val meta: File get() = File(File(trashRoot, META_DIR), "$id.json")

    /** `jsonEncode(entry.toJson())` of the 2.15.x: the same keys, in the same order. */
    fun toJson(): String = DartJson.encode(
        linkedMapOf(
            "id" to id,
            "originalPath" to originalPath,
            "name" to name,
            "isDir" to isDir,
            "size" to size,
            "deletedAt" to deletedAt.toIso8601String(),
        ),
    )

    companion object {
        const val ITEMS_DIR = "items"
        const val META_DIR = "meta"

        /** `FileCaps.trashMeta` of the 2.15.x. */
        const val META_MAX_BYTES = 256L * 1024

        /** What `_reserveId` produces: digits, optionally `_` and digits. Anything else is refused. */
        private val ID_PATTERN = Regex("""^\d+(_\d+)?$""")
        private val SEPARATORS = Regex("""[/\\]""")
        private val DRIVE = Regex("""^[A-Za-z]:[/\\]""")

        /** A path segment: not empty, not `.` or `..`, no separator, no NUL. */
        fun isSafeSegment(s: String): Boolean =
            s.isNotEmpty() && s != "." && s != ".." && '/' !in s && '\\' !in s && Char(0) !in s

        /** An absolute path with no `..` and no NUL: a possible target of a restore. */
        fun isPlausibleOriginalPath(p: String): Boolean =
            p.length >= 2 && Char(0) !in p && ".." !in p.split(SEPARATORS) && (p.startsWith("/") || DRIVE.containsMatchIn(p))

        /**
         * The entry [json] describes, or null when it cannot be read — or was forged. The metadata lives in
         * clear on shared storage: any app with broad access can rewrite it, and `id`, `name` and
         * `originalPath` feed file paths directly (`"id": "../.."` would aim Restore or Delete forever at
         * an arbitrary file). The 2.15.x rules, value types included: a `size` that is not a number, or
         * a `deletedAt` that is not a string, made Dart throw on its cast — the entry was skipped.
         */
        /** `<root>/meta/<id>.json` of the 2.15.x, or null: above [META_MAX_BYTES], not UTF-8, not JSON, forged. */
        @Suppress("SwallowedException") // an unreadable metadata file is skipped, as by the 2.15.x
        fun read(meta: File, trashRoot: File): TrashEntry? {
            // Capped WHILE reading (readCapped): `meta/` is on shared storage, where any app can drop a
            // file. A legitimate entry weighs a few hundred bytes; above 256 KiB it was not written by this app.
            return try {
                val bytes = readCapped(meta) ?: return null
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                (DartJson.decode(text) as? Map<*, *>)?.let { fromJson(it, trashRoot) }
            } catch (e: IOException) {
                null
            } catch (e: CharacterCodingException) {
                null
            } catch (e: DartJson.Malformed) {
                null
            }
        }

        fun fromJson(json: Map<*, *>, trashRoot: File): TrashEntry? {
            val id = json["id"] as? String
            val originalPath = json["originalPath"] as? String
            val name = json["name"] as? String
            // `DateTime.tryParse(j['deletedAt'] as String? ?? '')`: absent or unparsable, the entry is skipped.
            val deletedAt = (json["deletedAt"] as? String)?.let(DartDateTime::tryParse)
            val size = dartSize(json["size"])
            val valid = id != null && ID_PATTERN.matches(id) &&
                name != null && isSafeSegment(name) &&
                originalPath != null && isPlausibleOriginalPath(originalPath) &&
                deletedAt != null && size != null
            return if (valid) {
                TrashEntry(
                    id = id,
                    trashRoot = trashRoot,
                    originalPath = originalPath,
                    name = name,
                    isDir = json["isDir"] == true,
                    size = size,
                    deletedAt = deletedAt,
                )
            } else {
                null
            }
        }
    }
}

/**
 * `(size as num?)?.toInt() ?? -1` of the 2.15.x, or null where it threw — not a number, or infinite
 * (`1e400` decodes to infinity: Claude review R3). A double is cut toward zero, as Dart's toInt.
 */
private fun dartSize(raw: Any?): Long? = when (raw) {
    null -> -1L
    is Long -> raw
    is Double -> if (raw.isFinite()) raw.toLong() else null
    else -> null
}

/**
 * [file]'s bytes, or null beyond [TrashEntry.META_MAX_BYTES] — bounded WHILE reading: another app can
 * swap the file for a huge one between a size check and the read (GPT review R3).
 */
private fun readCapped(file: File): ByteArray? = FileInputStream(file).use { input ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER)
    while (true) {
        val n = input.read(buffer)
        if (n == -1) break
        out.write(buffer, 0, n)
        if (out.size() > TrashEntry.META_MAX_BYTES) return null
    }
    out.toByteArray()
}

private const val READ_BUFFER = 8 * 1024

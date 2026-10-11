package com.readfilestech.read_files_tech.data.trash

import com.readfilestech.read_files_tech.data.DartDateTime
import com.readfilestech.read_files_tech.data.DartJson
import java.io.File
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
            // Capped BEFORE reading: `meta/` is on shared storage, where any app can drop a file. A
            // legitimate entry weighs a few hundred bytes; above 256 KiB it was not written by this app.
            if (meta.length() > META_MAX_BYTES) return null
            return try {
                val text = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(meta.readBytes()))
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
            val size = json["size"]
            val valid = id != null && ID_PATTERN.matches(id) &&
                name != null && isSafeSegment(name) &&
                originalPath != null && isPlausibleOriginalPath(originalPath) &&
                deletedAt != null &&
                (size == null || size is Long || size is Double)
            return if (valid) {
                TrashEntry(
                    id = id,
                    trashRoot = trashRoot,
                    originalPath = originalPath,
                    name = name,
                    isDir = json["isDir"] == true,
                    // `(size as num?)?.toInt() ?? -1`: a double is cut toward zero, as Dart's toInt.
                    size = when (size) {
                        is Long -> size
                        is Double -> size.toLong()
                        else -> -1
                    },
                    deletedAt = deletedAt,
                )
            } else {
                null
            }
        }
    }
}

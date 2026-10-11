package com.readfilestech.read_files_tech.data.recents

import com.readfilestech.read_files_tech.data.DartDateTime
import com.readfilestech.read_files_tech.data.DartJson
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.PrefRead
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A file recently opened: `RecentFile` of files_tech_core (ef100dd, used by the 2.15.3). */
data class RecentFile(
    val path: String,
    val name: String,
    val lastOpened: DartDateTime,
    val sizeBytes: Long,
    val isFavorite: Boolean = false,
) {
    /** `jsonEncode(toJson())`: the same keys, in the same order. */
    fun toJson(): String = DartJson.encode(
        linkedMapOf(
            "path" to path,
            "name" to name,
            "lastOpened" to lastOpened.toIso8601String(),
            "sizeBytes" to sizeBytes,
            "isFavorite" to isFavorite,
        ),
    )

    companion object {
        /**
         * `RecentFile.fromJsonString` of the core, or null where it threw: a non-empty `path` and `name`,
         * a `lastOpened` that `DateTime.parse` accepts, a `sizeBytes` that is an int and not negative (a
         * double is refused: `is! int`), an `isFavorite` that is a bool or absent.
         */
        @Suppress("SwallowedException") // a malformed entry is skipped, as by the core
        fun fromJson(text: String): RecentFile? {
            val json = try {
                DartJson.decode(text) as? Map<*, *>
            } catch (expected: DartJson.Malformed) {
                null
            }
            val path = json?.get("path") as? String
            val name = json?.get("name") as? String
            val lastOpened = (json?.get("lastOpened") as? String)?.let(DartDateTime::tryParse)
            val size = json?.get("sizeBytes") as? Long
            val favorite = json?.get("isFavorite")
            val valid = !path.isNullOrEmpty() && !name.isNullOrEmpty() && lastOpened != null &&
                size != null && size >= 0 && (favorite == null || favorite is Boolean)
            return if (valid) RecentFile(path, name, lastOpened, size, favorite == true) else null
        }
    }
}

/**
 * The recent files, in the Flutter app's key (`recent_files`, a list of JSON texts) — port of
 * `RecentFilesService` of the core — and the paths the user removed from them (`recents_dismissed_paths`).
 *
 * One difference, on purpose (docs D-019): the core dropped an entry it could not read and rewrote the
 * list. Here an unreadable entry is kept as it is, at the end, and only an entry whose file is GONE is
 * cleaned up — a positive finding. A list that cannot be read at all is neither shown nor rewritten.
 */
@Singleton
class RecentFiles @Inject constructor(private val prefs: FlutterPreferences) {

    /** The recent files that still exist, the most recent first. */
    fun load(): List<RecentFile> {
        val stored = read() ?: return emptyList()
        // Dart's `File(path).exists()`: true for a file only, not for a directory.
        val present = stored.entries.filter { File(it.path).isFile }
        if (present.size != stored.entries.size) save(present, stored.unreadable)
        return present.sortedByDescending(RecentFile::lastOpened)
    }

    /**
     * [path] opened now: added at the front (or moved there), its favourite mark kept, the list cut at
     * [MAX_FILES]. Nothing happens for a file that does not exist, or a name that is not one.
     */
    fun addOrUpdate(path: String): List<RecentFile> {
        val file = File(path)
        return if (isRecordable(file)) {
            rewrite { entries ->
                val favorite = entries.firstOrNull { it.path == path }?.isFavorite ?: false
                val opened = RecentFile(path, file.name, DartDateTime.now(), file.length(), favorite)
                (listOf(opened) + entries.filter { it.path != path }).take(MAX_FILES)
            }
        } else {
            read()?.entries.orEmpty()
        }
    }

    /** A file that exists, under a name `PathSafe.basename` accepts. */
    private fun isRecordable(file: File): Boolean = file.isFile && file.name !in setOf("", ".", "..")

    fun remove(path: String): List<RecentFile> = rewrite { entries -> entries.filter { it.path != path } }

    fun toggleFavorite(path: String): List<RecentFile> =
        rewrite { entries -> entries.map { if (it.path == path) it.copy(isFavorite = !it.isFavorite) else it } }

    /** Paths removed from the recents by the user: never shown again. */
    fun dismissed(): Set<String> = (prefs.stringList(DISMISSED_KEY) as? PrefRead.Present)?.value?.toSet().orEmpty()

    /** Removes [path] from the recents for good. A list that cannot be read is left as it is (D-019). */
    fun dismiss(path: String) {
        val current = when (val read = prefs.stringList(DISMISSED_KEY)) {
            PrefRead.Absent -> emptyList()
            is PrefRead.Present -> read.value
            is PrefRead.Unreadable -> return
        }
        prefs.commit { putStringList(DISMISSED_KEY, (current.toCollection(LinkedHashSet()) + path).toList()) }
    }

    /** The stored list, readable entries apart from the others; null when the list itself is unreadable. */
    private class Stored(val entries: List<RecentFile>, val unreadable: List<String>)

    private fun read(): Stored? = when (val read = prefs.stringList(KEY)) {
        PrefRead.Absent -> Stored(emptyList(), emptyList())
        is PrefRead.Present -> {
            val parsed = read.value.map { it to RecentFile.fromJson(it) }
            Stored(parsed.mapNotNull { it.second }, parsed.filter { it.second == null }.map { it.first })
        }
        is PrefRead.Unreadable -> null
    }

    private fun rewrite(change: (List<RecentFile>) -> List<RecentFile>): List<RecentFile> {
        val stored = read() ?: return emptyList()
        val updated = change(stored.entries)
        save(updated, stored.unreadable)
        return updated
    }

    private fun save(entries: List<RecentFile>, unreadable: List<String>) {
        prefs.commit { putStringList(KEY, entries.map(RecentFile::toJson) + unreadable) }
    }

    companion object {
        const val KEY = "recent_files"
        const val DISMISSED_KEY = "recents_dismissed_paths"

        /** `maxFiles` of the core. */
        const val MAX_FILES = 20
    }
}

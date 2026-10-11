package com.readfilestech.read_files_tech.data.prefs

import com.readfilestech.read_files_tech.data.DartJson
import com.readfilestech.read_files_tech.data.DartText
import java.util.Locale

/**
 * What reading one inherited preference found.
 *
 * "Unreadable" is never collapsed into "absent" (docs D-019): a failure counter that cannot be read
 * must not come back as zero, and a list that cannot be decoded must not be rewritten as empty.
 */
sealed interface PrefRead<out T> {
    data object Absent : PrefRead<Nothing>

    data class Present<out T>(val value: T) : PrefRead<T>

    /** Present, but not of the expected type or format. Kept as it is; never rewritten on this basis. */
    data class Unreadable(val reason: String) : PrefRead<Nothing>
}

/**
 * The value, or null when absent OR unreadable. Only for values whose loss costs nothing (a display
 * hint); anything that guards the vault matches on [PrefRead] and treats Unreadable on its own.
 */
fun <T> PrefRead<T>.valueOrNull(): T? = (this as? PrefRead.Present)?.value

/**
 * The on-disk format of the Flutter app's preferences, decoded and encoded without Android.
 *
 * Read in the plugin the 2.15.x ships — shared_preferences_android 2.4.27 (pubspec.lock), its
 * `LegacySharedPreferencesPlugin.kt` and `lib/src/shared_preferences_android.dart` — not guessed:
 *
 * | Dart type | On disk |
 * |---|---|
 * | `bool` | `putBoolean` |
 * | `String` | `putString`, refused by the plugin if it starts with one of the prefixes below |
 * | `int` | **`putLong`** — `getInt` on it throws `ClassCastException` |
 * | `List<String>` | `putString(LIST_PREFIX + "!" + jsonEncode(list))` (2.4.23 to 2.4.27) |
 *
 * Only the JSON list form is read. Older plugin versions stored a list as `LIST_PREFIX` + Base64 of a
 * Java-serialised list, and the very first ones as a `Set<String>`, but no Read Files Tech ever
 * shipped them: its 29 `pubspec.lock` revisions, from 1.0.0 (2026-04-27) to 2.15.3, all lock
 * shared_preferences_android 2.4.23, 2.4.26 or 2.4.27, and those write only JSON (checked in their
 * `lib/src/shared_preferences_android.dart`, 2026-10-11). Reading the Java form would mean running
 * `ObjectInputStream` on the file at startup — a list declaring 2^31 elements is an
 * `OutOfMemoryError` before any check (external review of 2026-10-11) — for data that cannot exist.
 * Such a value is Unreadable, kept as it is (docs D-019).
 *
 * Read Files Tech stores no `double` (docs/INVENTAIRE-2.15.2.md §5.1), so none is decoded.
 */
object FlutterPrefsFormat {
    /** The file the plugin writes on Android. */
    const val FILE_NAME = "FlutterSharedPreferences"

    /** `SharedPreferences.getInstance()` prefixes every Dart key with it. */
    const val KEY_PREFIX = "flutter."

    /** Base64 of "This is the prefix for a list." (`SharedPreferencesPlugin.kt:35`). */
    const val LIST_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIGxpc3Qu"

    /** The JSON form (`:37`). `!` cannot occur in Base64, which tells the two list forms apart. */
    const val JSON_LIST_PREFIX = "$LIST_PREFIX!"

    private const val DOUBLE_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBEb3VibGUu"
    private const val BIG_INTEGER_PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBCaWdJbnRlZ2Vy"
    private val RESERVED_PREFIXES = listOf(LIST_PREFIX, DOUBLE_PREFIX, BIG_INTEGER_PREFIX)

    fun readLong(raw: Any?): PrefRead<Long> = when (raw) {
        null -> PrefRead.Absent
        is Long -> PrefRead.Present(raw)
        // Not what the plugin writes, but what native Android code would: accepted, not refused.
        is Int -> PrefRead.Present(raw.toLong())
        else -> PrefRead.Unreadable("expected an integer, found ${typeOf(raw)}")
    }

    fun readBoolean(raw: Any?): PrefRead<Boolean> = when (raw) {
        null -> PrefRead.Absent
        is Boolean -> PrefRead.Present(raw)
        else -> PrefRead.Unreadable("expected a boolean, found ${typeOf(raw)}")
    }

    fun readString(raw: Any?): PrefRead<String> = when {
        raw == null -> PrefRead.Absent
        raw !is String -> PrefRead.Unreadable("expected a string, found ${typeOf(raw)}")
        // The plugin never stores a plain string with these prefixes: such a value is a list (or a
        // number), and Dart would not have handed it back as a String either.
        RESERVED_PREFIXES.any { raw.startsWith(it) } -> PrefRead.Unreadable("a list or a number where a string is expected")
        else -> PrefRead.Present(raw)
    }

    fun readStringList(raw: Any?): PrefRead<List<String>> = when {
        raw == null -> PrefRead.Absent
        raw !is String -> PrefRead.Unreadable("expected a list, found ${typeOf(raw)}")
        raw.startsWith(JSON_LIST_PREFIX) ->
            DartJson.decodeStringList(raw.substring(JSON_LIST_PREFIX.length))?.let { PrefRead.Present(it) }
                ?: PrefRead.Unreadable("not a JSON list of strings that Dart's jsonDecode accepts")
        raw.startsWith(LIST_PREFIX) -> PrefRead.Unreadable("a Java-serialised list, a form no Read Files Tech wrote")
        else -> PrefRead.Unreadable("a string where a list is expected")
    }

    /**
     * A plain string as the plugin stores it: one with a reserved prefix is refused there too, and a lone
     * surrogate became U+FFFD on its way through the platform channel (DartText).
     */
    fun storableString(value: String): String {
        require(RESERVED_PREFIXES.none { value.startsWith(it) }) { "a string starting with a reserved prefix cannot be stored" }
        return DartText.wellFormed(value)
    }

    /** A list in the 2.4.27 form: [JSON_LIST_PREFIX] followed by exactly what the plugin stores. */
    fun encodeStringList(list: List<String>): String = JSON_LIST_PREFIX + dartJsonEncode(list.map(DartText::wellFormed))

    /**
     * Dart's `jsonEncode` for a list of strings, character for character (checked against Dart 3 in
     * FlutterPrefsFormatTest): `"` and `\` escaped, `\b \t \n \f \r` by name, the other characters
     * below U+0020 as `\u00xx` in lower case; everything else — `/`, DEL, U+2028, emoji — as it is.
     */
    internal fun dartJsonEncode(list: List<String>): String = buildString {
        append('[')
        list.forEachIndexed { index, item ->
            if (index > 0) append(',')
            appendDartJsonString(item)
        }
        append(']')
    }
}

/** One string as Dart's `jsonEncode` writes it — see [FlutterPrefsFormat.dartJsonEncode]. */
private fun StringBuilder.appendDartJsonString(value: String) {
    append('"')
    for (char in value) {
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\u000C' -> append("\\f")
            '\r' -> append("\\r")
            // Dart writes the other control characters as \u00xx, hexadecimal in lower case.
            else -> if (char < ' ') append("\\u").append(String.format(Locale.ROOT, "%04x", char.code)) else append(char)
        }
    }
    append('"')
}

private fun typeOf(raw: Any): String = raw::class.java.simpleName

package com.readfilestech.read_files_tech.data.prefs

import org.json.JSONArray
import org.json.JSONException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.util.Base64
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
 * | `List<String>` | `putString(LIST_PREFIX + "!" + jsonEncode(list))` (2.4.27) |
 *
 * Older plugin versions stored lists as `LIST_PREFIX` + Base64 of a Java-serialised list, and the
 * very first ones as a `Set<String>`: an install that dates back to them may still carry those, so
 * all three are read. Writing uses the 2.4.27 JSON form only, the one the 2.15.x writes — a second
 * format on the user's disk would make the two periods indistinguishable.
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

    fun readStringList(raw: Any?): PrefRead<List<String>> = when (raw) {
        null -> PrefRead.Absent
        is String -> decodeList(raw)
        // The plugin's first versions used putStringSet. The order is lost: the format lost it.
        is Set<*> ->
            if (raw.all { it is String }) {
                PrefRead.Present(raw.map { it as String })
            } else {
                PrefRead.Unreadable("a set holding something else than strings")
            }
        else -> PrefRead.Unreadable("expected a list, found ${typeOf(raw)}")
    }

    /** A plain string as the plugin's `setString` accepts it: one with a reserved prefix is refused there too. */
    fun requireStorableString(value: String) {
        require(RESERVED_PREFIXES.none { value.startsWith(it) }) { "a string starting with a reserved prefix cannot be stored" }
    }

    /** A list in the 2.4.27 form: [JSON_LIST_PREFIX] followed by exactly what Dart's `jsonEncode` writes. */
    fun encodeStringList(list: List<String>): String = JSON_LIST_PREFIX + dartJsonEncode(list)

    private fun decodeList(raw: String): PrefRead<List<String>> = when {
        raw.startsWith(JSON_LIST_PREFIX) -> decodeJsonList(raw.substring(JSON_LIST_PREFIX.length))
        raw.startsWith(LIST_PREFIX) -> decodeSerializedList(raw.substring(LIST_PREFIX.length))
        else -> PrefRead.Unreadable("a string where a list is expected")
    }

    private fun decodeJsonList(json: String): PrefRead<List<String>> = try {
        val array = JSONArray(json)
        val items = (0 until array.length()).map { array.get(it) }
        if (items.all { it is String }) {
            PrefRead.Present(items.map { it as String })
        } else {
            PrefRead.Unreadable("a JSON list holding something else than strings")
        }
    } catch (e: JSONException) {
        PrefRead.Unreadable("malformed JSON list: ${e.message}")
    }

    private fun decodeSerializedList(base64: String): PrefRead<List<String>> = try {
        // MIME decoder: Android's Base64.DEFAULT, which the plugin used, breaks lines every 76 characters.
        val bytes = Base64.getMimeDecoder().decode(base64)
        StringListOnlyInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val list = stream.readObject() as? List<*>
            if (list != null && list.all { it is String }) {
                PrefRead.Present(list.map { it as String })
            } else {
                PrefRead.Unreadable("a serialised object that is not a list of strings")
            }
        }
    } catch (e: IOException) {
        PrefRead.Unreadable("unreadable serialised list: ${e.message}")
    } catch (e: ClassNotFoundException) {
        PrefRead.Unreadable("serialised list refers to a refused class: ${e.message}")
    } catch (e: IllegalArgumentException) {
        PrefRead.Unreadable("invalid Base64 in a serialised list: ${e.message}")
    }

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

    /**
     * An `ObjectInputStream` that refuses anything but a list of strings. Deserialising Java lets the
     * data choose the classes to instantiate; a preferences file can be planted on a rooted phone or
     * carried by a migration tool. The list of accepted classes is the plugin's own
     * (`StringListObjectInputStream`): not a local invention, and loosening it would reopen a door the
     * plugin's authors closed on purpose.
     */
    private class StringListOnlyInputStream(input: InputStream) : ObjectInputStream(input) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> {
            if (desc.name !in ACCEPTED_CLASSES) throw ClassNotFoundException(desc.name)
            return super.resolveClass(desc)
        }

        private companion object {
            val ACCEPTED_CLASSES = setOf(
                "java.util.Arrays\$ArrayList",
                "java.util.ArrayList",
                "java.lang.String",
                "[Ljava.lang.String;",
            )
        }
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

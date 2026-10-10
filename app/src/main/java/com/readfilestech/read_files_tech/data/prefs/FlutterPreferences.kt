package com.readfilestech.read_files_tech.data.prefs

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The preferences of the Flutter app, read AND written in place, in their own format (docs D-003,
 * D-018): one store for every inherited value, so a successful unlock that resets the failure
 * counter, or a vault reset that writes a new salt, cannot be undone by an older copy read first.
 *
 * Keys are the bare Dart keys: the `flutter.` prefix is added here, once. Forgetting it raises no
 * error — the read finds nothing and falls back, which looks like "the user never changed this".
 */
@Singleton
class FlutterPreferences @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FlutterPrefsFormat.FILE_NAME, Context.MODE_PRIVATE)

    fun long(key: String): PrefRead<Long> = FlutterPrefsFormat.readLong(raw(key))

    fun boolean(key: String): PrefRead<Boolean> = FlutterPrefsFormat.readBoolean(raw(key))

    fun string(key: String): PrefRead<String> = FlutterPrefsFormat.readString(raw(key))

    fun stringList(key: String): PrefRead<List<String>> = FlutterPrefsFormat.readStringList(raw(key))

    fun contains(key: String): Boolean = prefs.contains(prefixed(key))

    /** The bare Dart keys present in the file (prefix removed). Keys without the prefix are not Dart's. */
    fun keys(): Set<String> = prefs.all.keys
        .filter { it.startsWith(FlutterPrefsFormat.KEY_PREFIX) }
        .map { it.removePrefix(FlutterPrefsFormat.KEY_PREFIX) }
        .toSet()

    /**
     * Several writes in ONE `commit()`, and whether the disk took them.
     *
     * `commit()`, never `apply()`: `apply()` returns at once and writes later, so a failure counter
     * raised that way and a process killed a moment after would hand out a fresh attempt. Callers that
     * guard something check the result and do not report success on `false`.
     */
    fun commit(changes: Edition.() -> Unit): Boolean {
        val editor = prefs.edit()
        Edition(editor).changes()
        return editor.commit()
    }

    /**
     * Erases EVERY key of the file except the bare Dart keys in [keep] — the panic.
     *
     * An allow-list, never a deny-list: a key added later is erased by default, and keeping one is a
     * decision. Keys without the `flutter.` prefix are erased too: the panic leaves nothing it does not
     * know.
     *
     * @return the number of keys erased.
     * @throws IllegalStateException if the file refused the write. Never swallow it: traces would
     *   survive a panic that reports itself complete.
     */
    fun clearAllExcept(keep: Set<String>): Int {
        val kept = keep.map(::prefixed).toSet()
        val toErase = prefs.all.keys - kept
        if (toErase.isEmpty()) return 0
        val editor = prefs.edit()
        toErase.forEach(editor::remove)
        check(editor.commit()) { "the preferences file refused to erase ${toErase.size} keys" }
        return toErase.size
    }

    /** The writes this file's format allows, keys prefixed, values in the plugin's own encoding. */
    inner class Edition internal constructor(private val editor: SharedPreferences.Editor) {
        fun putString(key: String, value: String) {
            FlutterPrefsFormat.requireStorableString(value)
            editor.putString(prefixed(key), value)
        }

        fun putBoolean(key: String, value: Boolean) {
            editor.putBoolean(prefixed(key), value)
        }

        /** A Dart `int`, stored as a `Long` like the plugin does. */
        fun putLong(key: String, value: Long) {
            editor.putLong(prefixed(key), value)
        }

        /** A Dart `List<String>`, in the JSON form the 2.15.x writes. */
        fun putStringList(key: String, value: List<String>) {
            editor.putString(prefixed(key), FlutterPrefsFormat.encodeStringList(value))
        }

        fun remove(key: String) {
            editor.remove(prefixed(key))
        }
    }

    private fun raw(key: String): Any? = prefs.all[prefixed(key)]

    private fun prefixed(key: String) = FlutterPrefsFormat.KEY_PREFIX + key
}

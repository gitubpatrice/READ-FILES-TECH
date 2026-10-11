package com.readfilestech.read_files_tech.testing

import android.content.SharedPreferences

/**
 * A `SharedPreferences` for JVM tests, with the platform's commit semantics: an edition applies all its
 * changes at once, or none ([failCommits]). Values are kept with their exact type — a test plants a
 * `String` where the plugin writes a `Long` to check that the port does not take it for absent.
 */
class InMemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any>()

    /** When true, every commit returns false and changes nothing — a full disk, a read-only file. */
    var failCommits = false

    /** Called at each commit with the keys it writes: a test acts at that instant of an operation. */
    var onCommit: ((Set<String>) -> Unit)? = null

    /** Plants a raw value, as an older version or another tool would have left it. */
    fun plant(key: String, value: Any) {
        values[key] = value
    }

    override fun getAll(): Map<String, *> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as Set<String>?)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue

    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val puts = linkedMapOf<String, Any>()
        private val removes = linkedSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values?.toSet())

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String): SharedPreferences.Editor {
            removes += key
            puts.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            onCommit?.invoke(puts.keys.toSet())
            if (failCommits) return false
            if (clear) values.clear()
            removes.forEach(values::remove)
            values.putAll(puts)
            return true
        }

        override fun apply() {
            commit()
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            if (value == null) return remove(key)
            puts[key] = value
            removes.remove(key)
            return this
        }
    }
}

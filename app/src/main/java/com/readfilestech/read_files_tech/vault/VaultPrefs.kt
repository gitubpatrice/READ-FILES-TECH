package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.PrefRead

/**
 * A vault key's value, null when absent. Unreadable is NOT null (docs D-019): the vault refuses to act
 * on a state it cannot read, and rewrites nothing.
 *
 * @throws VaultCorruptedException
 */
internal fun <T> PrefRead<T>.orCorrupted(key: String): T? = when (this) {
    PrefRead.Absent -> null
    is PrefRead.Present -> value
    is PrefRead.Unreadable -> throw VaultCorruptedException("$key: $reason")
}

/** A stored list, empty when absent. @throws VaultCorruptedException it cannot be read. */
internal fun FlutterPreferences.listOrEmpty(key: String): List<String> = stringList(key).orCorrupted(key).orEmpty()

/** One commit that guards the vault: a refusal is reported, never taken for done. */
internal fun FlutterPreferences.commitOrThrow(what: String, changes: FlutterPreferences.Edition.() -> Unit) {
    if (!commit(changes)) throw VaultStorageException("the preferences file refused to write $what")
}

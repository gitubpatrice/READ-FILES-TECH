package com.readfilestech.read_files_tech.data.browse

import java.text.CollationKey
import java.text.Collator
import java.util.Locale

/**
 * The order of the names a user reads, by the rules of the app's language (docs D-033): "École" between "Ecole"
 * and "Écran", where the code points put it after "Zèbre" — and German's "Äpfel" after "Zug". Case and accents
 * count only between names otherwise the same; two names the language holds equal ("été" written as one character
 * or as "e" and its accent) keep the order of their code points, so a sort gives the same order at every run.
 *
 * Each name's key is computed once per sort, not once per comparison. A [Collator] is not thread-safe: one
 * [NameOrder] per sort.
 */
class NameOrder(locale: Locale = Locale.getDefault()) {
    private val collator: Collator = Collator.getInstance(locale)

    /** The order of [items] by the name [nameOf] gives each. */
    fun <T> comparing(items: List<T>, nameOf: (T) -> String): Comparator<T> {
        val keys = items.associateWith { NameKey(listOf(segment(nameOf(it)))) }
        return compareBy { keys.getValue(it) }
    }

    /**
     * The order of [items] by the path [pathOf] gives each, folder by folder: a folder's entries stay together —
     * "e/y", then "é/x" and "é/z" — where comparing whole paths would put "é/x" before "e/y" (the letters decide
     * before the accents) and mix the two folders. A folder's name repeats in every path under it: its key is
     * computed once.
     */
    fun <T> comparingPaths(items: List<T>, pathOf: (T) -> String): Comparator<T> {
        val segments = HashMap<String, Segment>()
        fun segmentOf(text: String) = segments.getOrPut(text) { segment(text) }
        val keys = items.associateWith { item ->
            NameKey(pathOf(item).split('/').map(::segmentOf))
        }
        return compareBy { keys.getValue(it) }
    }

    /** [items] sorted by name. */
    fun <T> sort(items: List<T>, nameOf: (T) -> String): List<T> = items.sortedWith(comparing(items, nameOf))

    /** [items] sorted by path, folder by folder. */
    fun <T> sortPaths(items: List<T>, pathOf: (T) -> String): List<T> = items.sortedWith(comparingPaths(items, pathOf))

    private fun segment(text: String) = Segment(collator.getCollationKey(text), text)

    /**
     * A name, or a folder of a path: its key in the language, then its text to settle what the language holds
     * equal — before the next segment, or two folders the language holds equal would mix their entries.
     */
    private class Segment(val key: CollationKey, val text: String) : Comparable<Segment> {
        override fun compareTo(other: Segment): Int = key.compareTo(other.key).takeIf { it != 0 } ?: text.compareTo(other.text)
    }

    /** The segments in order; a folder before what it holds. */
    private class NameKey(val segments: List<Segment>) : Comparable<NameKey> {
        override fun compareTo(other: NameKey): Int {
            for (i in 0 until minOf(segments.size, other.segments.size)) {
                val order = segments[i].compareTo(other.segments[i])
                if (order != 0) return order
            }
            return segments.size.compareTo(other.segments.size)
        }
    }
}

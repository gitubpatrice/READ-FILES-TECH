package com.readfilestech.read_files_tech.data.viewer.pdf

/**
 * Values put aside for reuse — the rendered pages the PDF viewer scrolled past — the least recently given
 * back RECYCLED once they weigh more than [maxBytes]. A value is either here or owned by whoever took it,
 * never both: what the screen draws is never in the cache, so never recycled under it. Thread-safe; the
 * recycling runs outside the lock. Pure, tested on the JVM.
 */
class RecyclingCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
    private val recycle: (V) -> Unit,
) {
    private class Kept<V>(val value: V, val bytes: Long)

    // Insertion order: the first given back is the first recycled.
    private val kept = LinkedHashMap<K, Kept<V>>()
    private var bytes = 0L
    private var closed = false

    /** The value kept for [key], taken out: the caller owns it, and gives it back with [give]. */
    @Synchronized
    fun take(key: K): V? = kept.remove(key)?.also { bytes -= it.bytes }?.value

    /**
     * [value] kept for [key]; past [maxBytes], the oldest are recycled — never [value] itself, kept even
     * alone above the bound until the next one comes. A value already kept under [key] is recycled. After
     * [clear], [value] is recycled at once.
     */
    fun give(key: K, value: V) {
        val dropped = ArrayList<V>()
        synchronized(this) {
            if (closed) {
                dropped += value
            } else {
                kept.remove(key)?.let {
                    bytes -= it.bytes
                    dropped += it.value
                }
                val size = sizeOf(value)
                kept[key] = Kept(value, size)
                bytes += size
                val oldest = kept.entries.iterator()
                while (bytes > maxBytes) {
                    val entry = oldest.next()
                    if (entry.key == key) break
                    oldest.remove()
                    bytes -= entry.value.bytes
                    dropped += entry.value.value
                }
            }
        }
        dropped.forEach(recycle)
    }

    /** Recycles everything kept; a value given back afterwards is recycled at once. */
    fun clear() {
        val dropped = synchronized(this) {
            closed = true
            bytes = 0
            kept.values.map { it.value }.also { kept.clear() }
        }
        dropped.forEach(recycle)
    }
}

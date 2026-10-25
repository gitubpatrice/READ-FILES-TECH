package com.readfilestech.read_files_tech.data.search

/**
 * A search's results handed over in batches — 20, or whatever came in 200 ms — and its progress every 200
 * files: the screen redraws in steps, not once per file (the 2.15.3's buffer and flush of
 * `global_search_screen.dart:28-54`, copied into `content_search_screen.dart`).
 */
internal class SearchBatches<T>(
    private val onFound: suspend (List<T>) -> Unit,
    private val onScanned: suspend (Int) -> Unit,
) {
    private val pending = mutableListOf<T>()
    private var lastFlush = System.currentTimeMillis()
    private var reported = 0

    fun add(hit: T) {
        pending += hit
    }

    /** After each file: [scanned] files looked at so far. */
    suspend fun report(scanned: Int) {
        if (scanned - reported >= PROGRESS_EVERY) {
            reported = scanned
            onScanned(reported)
        }
        val late = System.currentTimeMillis() - lastFlush > FLUSH_MS
        if (pending.size >= BATCH || (pending.isNotEmpty() && late)) flush()
    }

    /** The end: what is left, then the count of files looked at. */
    suspend fun finish(scanned: Int) {
        if (pending.isNotEmpty()) flush()
        onScanned(scanned)
    }

    private suspend fun flush() {
        onFound(pending.toList())
        pending.clear()
        lastFlush = System.currentTimeMillis()
    }

    private companion object {
        const val BATCH = 20
        const val FLUSH_MS = 200L
        const val PROGRESS_EVERY = 200
    }
}

package com.readfilestech.read_files_tech.data.viewer.text

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.Arrays

/**
 * The syntax colours of a [TextDocument] in [language], computed for a row when it is shown — the 2.15.3
 * laid the whole file, up to 50 MiB, out in one HighlightView (INVENTAIRE F38).
 *
 * What a row enters in — a comment or a string opened rows above it — comes from the start of the file:
 * [scan] reads every row once, from the bytes, without decoding them ([RawRows]), and keeps the state a row
 * enters in every [CHECKPOINT_ROWS] rows, or every [CHECKPOINT_BYTES] bytes when rows are long. A row's
 * colours are computed from the checkpoint before it, reading again at most that much — or from the row
 * computed last when it lies between: rows shown one after the other read one row each. So they are exact
 * everywhere — a comment opened on the first line and never closed colours all of a 50 MiB file, as the
 * language says — once the scan has passed the row; [spans] of a row it has not reached waits for it. The
 * scan holds no text, and its checkpoints take 8 bytes each: 1.6 MB for 50 MiB of line feeds, 52 million rows.
 */
class CodeColouring(private val document: TextDocument, val language: CodeLanguage) {
    private val lock = Any()
    private var rows = IntArray(INITIAL_CHECKPOINTS)
    private var states = IntArray(INITIAL_CHECKPOINTS)

    /** The checkpoints kept; the first one, row 0 entered in state 0, from the start. */
    private var count = 1

    /** The last row with a checkpoint at or after every row up to it kept — [TextDocument.rowCount] once all are. */
    private val reached = MutableStateFlow(0)

    @Volatile
    private var failed = false

    /** The row whose colours were computed last, and the state it enters in: where the next row's may start. */
    private var lastRow = 0
    private var lastState = 0

    /** How many checkpoints are kept. */
    internal val checkpoints: Int get() = synchronized(lock) { count }

    /**
     * Reads the file once, keeping the checkpoints, and cancellable between two. If it stops before the end
     * (cancelled, or failing), the rows waiting for it and the rows asked for after get no colours.
     */
    suspend fun scan() {
        try {
            val context = currentCoroutineContext()
            val tokenizer = language.tokenizer()
            val raw = document.rawRows(0)
            var state = 0
            var index = 0
            var rowsSince = 0
            var bytesSince = 0
            while (raw.next()) {
                if (rowsSince >= CHECKPOINT_ROWS || bytesSince >= CHECKPOINT_BYTES) {
                    keep(index, state)
                    rowsSince = 0
                    bytesSince = 0
                    context.ensureActive()
                }
                state = tokenizer.tokenize(raw, state, raw.endsLine, null)
                rowsSince++
                bytesSince += raw.byteLength
                index++
            }
            synchronized(lock) {
                rows = rows.copyOf(count)
                states = states.copyOf(count)
            }
            reached.value = document.rowCount
        } finally {
            if (reached.value < document.rowCount) {
                failed = true
                reached.value = Int.MAX_VALUE
            }
        }
    }

    /** The colours of row [index] (of its text, [TextDocument.row]) — once [scan] has passed it. */
    suspend fun spans(index: Int): List<CodeSpan> {
        require(index in 0 until document.rowCount) { "row $index of ${document.rowCount}" }
        reached.first { it >= index }
        if (failed) return emptyList()
        val (from, entered) = synchronized(lock) {
            val found = Arrays.binarySearch(rows, 0, count, index)
            val checkpoint = if (found >= 0) found else -found - 2
            if (lastRow in rows[checkpoint]..index) lastRow to lastState else rows[checkpoint] to states[checkpoint]
        }
        val tokenizer = language.tokenizer()
        var state = entered
        if (from < index) {
            val raw = document.rawRows(from)
            repeat(index - from) {
                raw.next()
                state = tokenizer.tokenize(raw, state, raw.endsLine, null)
            }
        }
        synchronized(lock) {
            lastRow = index
            lastState = state
        }
        val spans = ArrayList<CodeSpan>()
        tokenizer.tokenize(document.row(index), state, !document.continues(index), spans)
        return spans
    }

    private fun keep(row: Int, state: Int) {
        synchronized(lock) {
            if (count == rows.size) {
                rows = rows.copyOf(count * 2)
                states = states.copyOf(count * 2)
            }
            rows[count] = row
            states[count] = state
            count++
        }
        reached.value = row
    }

    companion object {
        /** A checkpoint at least every this many rows... */
        const val CHECKPOINT_ROWS = 256

        /** ...and every this many bytes: a row's colours read again 64 KiB at most, plus a row. */
        const val CHECKPOINT_BYTES = 64 * 1024

        private const val INITIAL_CHECKPOINTS = 64
    }
}

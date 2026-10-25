package com.readfilestech.read_files_tech.data.outputs

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream

/**
 * A new output of [category], `<name>_<stamp>.<extension>` ([OutputStorage.reserve]), filled by [fill] and
 * written atomically ([OutputStorage.write]); the reservation given back if anything fails — a stop during the
 * last write too, checked before the file is committed (Claude review R18: a PNG of 64 Mpx compressed for
 * seconds was published after a stop). [fill] runs on the caller's thread and cannot suspend: it checks for a
 * stop between its pieces of work itself.
 *
 * @throws NoOutputLocationException no folder could take it.
 */
suspend fun OutputStorage.create(category: OutputCategory, suggestedName: String, extension: String, fill: (OutputStream) -> Unit): File {
    val job = currentCoroutineContext()
    return create(category, suggestedName, extension, { job.ensureActive() }, fill)
}

/**
 * [create] for a caller that does not suspend: [ensureActive] is the stop check, called once [fill] is done,
 * before the file is committed — throwing there (a cancellation) leaves no output, nor the reservation.
 *
 * @throws NoOutputLocationException no folder could take it.
 */
fun OutputStorage.create(
    category: OutputCategory,
    suggestedName: String,
    extension: String,
    ensureActive: () -> Unit,
    fill: (OutputStream) -> Unit,
): File {
    val reserved = reserve(category, suggestedName, extension)
    var written = false
    try {
        write(reserved) { stream ->
            fill(stream)
            ensureActive()
        }
        written = true
    } finally {
        if (!written) discard(reserved)
    }
    return reserved
}

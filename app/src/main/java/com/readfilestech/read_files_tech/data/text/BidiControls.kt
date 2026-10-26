package com.readfilestech.read_files_tech.data.text

/**
 * A name or an address that came from a file, made safe to show: its bidirectional controls — the embeddings and
 * overrides U+202A–U+202E, the isolates U+2066–U+2069, the marks U+200E, U+200F and U+061C — shown as U+FFFD.
 * Left in, an entry named `Facture<U+202E>fdp.apk` reads « Facturekpa.pdf »: an app passed for a PDF, or one host
 * shown for another (security audit of the 3.0, A-1 and A-2). They change nothing but the order of what is drawn.
 */
fun String.withoutBidiControls(): String {
    if (none(::isBidiControl)) return this
    return buildString(length) { this@withoutBidiControls.forEach { append(if (isBidiControl(it)) REPLACEMENT else it) } }
}

private fun isBidiControl(c: Char): Boolean =
    c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\u200E' || c == '\u200F' || c == '\u061C'

private const val REPLACEMENT = '\uFFFD'

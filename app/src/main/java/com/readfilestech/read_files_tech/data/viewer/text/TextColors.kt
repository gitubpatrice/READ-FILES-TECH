package com.readfilestech.read_files_tech.data.viewer.text

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A colour written in a text, as written ([code]) and as an opaque ARGB value. */
data class TextColor(val code: String, val argb: Int)

/**
 * The colours of a text — `extractColors` of the 2.15.3 (`lib/utils/color_extract.dart`), for the
 * viewer's colour bar: `#RGB` and `#RRGGBB` first, then `rgb(…)` and `rgba(…)`, each code once, in the
 * order found. Two changes, both defects of the original:
 * - `rgb(300, 0, 0)` was masked to 44 by `Color.fromRGBO`; a component above 255 is clamped, as CSS does;
 * - the list had no bound — a text of a million codes built a million chips; it stops at [MAX_COLORS].
 *
 * The 2.15.3 searched the whole text as one string; here it is searched in pieces of [CHUNK_BYTES], each
 * decoded at once (searched row by row, ten million short lines took 84 s on an emulator). A piece
 * borrows the start of the next one, so that a code across the cut is found whole, and only once.
 *
 * The word boundary after a hex code is Dart's (an ASCII letter, digit or `_` ends nothing), not ICU's,
 * for which `é` is a word character too.
 */
object TextColors {
    const val MAX_COLORS = 200

    private val HEX = Regex("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})(?![0-9A-Za-z_])")

    // `\s{0,16}`: Dart's `\s*`, bounded so that a match never runs further than OVERLAP past a cut.
    private val RGB = Regex("rgba?\\(\\s{0,16}(\\d{1,4})\\s{0,16},\\s{0,16}(\\d{1,4})\\s{0,16},\\s{0,16}(\\d{1,4})")

    private const val CHUNK_BYTES = 256 * 1024

    /** What a piece borrows from the next, in characters — longer than any code the patterns match. */
    private const val OVERLAP = 128

    /** Enough bytes for [OVERLAP] characters in any encoding (UTF-8 and UTF-16 take at most 4). */
    private const val OVERLAP_BYTES = OVERLAP * 4
    private const val MAX_COMPONENT = 255

    /** `#RGB`, each digit doubled into `#RRGGBB`. */
    private const val SHORT_HEX = 3
    private const val OPAQUE = 0xFF shl 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    suspend fun extract(document: TextDocument, max: Int = MAX_COLORS): List<TextColor> {
        val hex = LinkedHashMap<String, TextColor>()
        val rgb = LinkedHashMap<String, TextColor>()
        var from = 0
        // Every hex code comes before every rgb() one: once [max] hex codes are found, nothing else shows.
        while (from < document.rowCount && hex.size < max) {
            currentCoroutineContext().ensureActive()
            val to = document.rowAfter(from, CHUNK_BYTES).coerceAtLeast(from + 1)
            val own = document.text(from, to)
            val window = if (to < document.rowCount) own + borrowed(document, to) else own
            HEX.findAll(window).filter { it.range.first < own.length }.forEach { match ->
                if (hex.size < max) hex.getOrPut(match.value) { TextColor(match.value, hexArgb(match.groupValues[1])) }
            }
            RGB.findAll(window).filter { it.range.first < own.length }.forEach { match ->
                val code = match.value + ")"
                if (rgb.size < max && code !in rgb) {
                    val (r, g, b) = match.groupValues.drop(1).map { it.toInt().coerceAtMost(MAX_COMPONENT) }
                    rgb[code] = TextColor(code, OPAQUE or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b)
                }
            }
            from = to
        }
        return (hex.values + rgb.values).take(max)
    }

    /** The first [OVERLAP] characters from row [from] on. */
    private fun borrowed(document: TextDocument, from: Int): String =
        document.text(from, document.rowAfter(from, OVERLAP_BYTES).coerceAtLeast(from + 1)).take(OVERLAP)

    private fun hexArgb(digits: String): Int {
        val six = if (digits.length == SHORT_HEX) digits.map { "$it$it" }.joinToString("") else digits
        return OPAQUE or six.toInt(radix = 16)
    }
}

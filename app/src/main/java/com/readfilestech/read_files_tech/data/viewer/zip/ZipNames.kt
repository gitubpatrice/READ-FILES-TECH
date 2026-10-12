package com.readfilestech.read_files_tech.data.viewer.zip

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * How an entry's name is decoded. Flagged UTF-8 (general purpose bit 11), it is UTF-8. Unflagged, the
 * format says IBM code page 437 — and writers disagree: 7-Zip writes the OEM code page of its Windows
 * (850 in France: "Straße" is `53 74 72 61 E1 65`), Info-ZIP and macOS write UTF-8 without the flag. So
 * an unflagged name is read as UTF-8 when it is valid UTF-8, as code page 437 otherwise — per entry.
 *
 * java.util.zip.ZipFile decodes every unflagged name of an archive with one charset, UTF-8 by default,
 * and refuses the WHOLE archive on the first name that is not: a 7-Zip archive holding "Straße.csv"
 * does not open (measured on JDK 17, `invalid CEN header (bad entry name)`; the same check is in the
 * ZipFile of Android, `ZipFile.java:1361` of the API 36.1 sources).
 */
internal object ZipNames {
    /**
     * Bytes 0x80 to 0xFF of code page 437, embedded rather than asked of `Charset.forName("IBM437")`,
     * which no Android version promises. Checked against the JDK's IBM437 by the tests.
     */
    private val CP437_HIGH: String =
        "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐" +
            "└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■" +
            // 0xFF, the no-break space: written by its code, an invisible character would read as a space.
            Char(NO_BREAK_SPACE)

    private const val NO_BREAK_SPACE = 0xA0
    private const val ASCII_END = 0x80
    private const val BYTE = 0xFF

    fun decode(bytes: ByteArray, utf8Flag: Boolean): String = when {
        // A malformed flagged name keeps its other characters: `�` where the bytes were wrong.
        utf8Flag -> String(bytes, Charsets.UTF_8)
        bytes.all { it >= 0 } -> String(bytes, Charsets.US_ASCII)
        else -> strictUtf8(bytes) ?: cp437(bytes)
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (expected: CharacterCodingException) {
        null
    }

    private fun cp437(bytes: ByteArray): String = buildString(bytes.size) {
        bytes.forEach { byte ->
            val b = byte.toInt() and BYTE
            append(if (b < ASCII_END) b.toChar() else CP437_HIGH[b - ASCII_END])
        }
    }
}

package com.readfilestech.read_files_tech.data

/**
 * Text as the Flutter app turned it into bytes, where Kotlin and Dart differ.
 *
 * A Kotlin `String` may hold a lone UTF-16 surrogate. Dart's `utf8.encode` writes it as U+FFFD
 * (`EF BF BD`); Java's `toByteArray(UTF_8)` writes `?` (`3F`). A password, a vault file name in an AAD
 * or a backup entry name holding one would give another key, another tag, another name than the 2.15.x
 * (external review of 2026-10-11). Unlikely from a keyboard, but a byte of difference here is a vault
 * that does not open.
 */
object DartText {
    /** U+FFFD, built from its code: a literal would be one more character an editor can alter. */
    private const val REPLACEMENT_CODE = 0xFFFD
    private val REPLACEMENT = Char(REPLACEMENT_CODE)

    /** [text] with every lone surrogate replaced by U+FFFD, as Dart's UTF-8 encoder sees it. */
    fun wellFormed(text: String): String {
        if (text.none { it.isSurrogate() }) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val pairs = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            when {
                pairs -> {
                    out.append(c).append(text[i + 1])
                    i += 2
                }
                c.isSurrogate() -> {
                    out.append(REPLACEMENT)
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Dart's `utf8.encode`. */
    fun utf8(text: String): ByteArray = wellFormed(text).toByteArray(Charsets.UTF_8)
}

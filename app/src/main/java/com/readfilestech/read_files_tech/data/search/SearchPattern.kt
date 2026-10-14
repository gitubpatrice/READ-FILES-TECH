package com.readfilestech.read_files_tech.data.search

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import com.readfilestech.read_files_tech.data.files.FileCaps

/** Why a pattern the user typed is refused; the screens say it in words. */
enum class PatternProblem {
    /** Longer than [FileCaps.PATTERN_CHARS]. */
    TOO_LONG,

    /** Not a regular expression RE2 reads — a syntax error, or a look-around or back-reference it does not do. */
    INVALID,

    /** Counted repetitions making more than [FileCaps.PATTERN_REPEATS] copies. */
    TOO_MANY_REPEATS,

    /** A replacement naming a group the pattern does not have (`$3` of a pattern of two groups). */
    BAD_REPLACEMENT,
}

class InvalidPatternException(val problem: PatternProblem) : IllegalArgumentException("pattern refused: $problem")

/**
 * A text to find, typed by the user: a word, or a regular expression — HOSTILE either way. Both are matched
 * by RE2/J, in a time linear in the text whatever the pattern: Android's java.util.regex is ICU's
 * backtracking engine, where `(a+)+$` on a file name of 50 `a` and a `!` runs for ever, and nothing can stop
 * it — it copies its input to native memory, so no check of ours runs while it works. The 2.15.3 capped the
 * pattern at 200 characters (`bulk_rename_screen.dart:71-77`, `content_search_screen.dart:76`), which bounds
 * no time (inventory F3). A pattern refused says why ([InvalidPatternException]); the 2.15.3's content search
 * stayed blocked after one (F4).
 *
 * RE2 syntax is Perl's without look-arounds and back-references; `$1`, `${name}` in a replacement.
 */
class SearchPattern private constructor(private val pattern: Pattern) {
    /**
     * The first match in [text] from [from] on, or null. [check] runs every few thousand characters the
     * engine reads — a cancellation, a deadline: a linear time is still long on a line of 2 MiB.
     */
    fun find(text: CharSequence, from: Int = 0, check: () -> Unit = {}): IntRange? {
        val matcher = pattern.matcher(CheckedChars(text, check))
        return if (matcher.find(from)) matcher.start() until matcher.end() else null
    }

    /**
     * [text] with each match replaced by [replacement], where `$1`… and `${name}` stand for a group and
     * `\` makes the next character plain. @throws InvalidPatternException a group the pattern does not have.
     */
    // RE2/J says "$9 of a pattern of two groups" by an IndexOutOfBoundsException, and by nothing narrower.
    @Suppress("TooGenericExceptionCaught")
    fun replaceAll(text: String, replacement: String, check: () -> Unit = {}): String = try {
        pattern.matcher(CheckedChars(text, check)).replaceAll(replacement)
    } catch (expected: IndexOutOfBoundsException) {
        throw InvalidPatternException(PatternProblem.BAD_REPLACEMENT)
    } catch (expected: IllegalArgumentException) {
        // `${name}` of no group, or without its `}`.
        throw InvalidPatternException(PatternProblem.BAD_REPLACEMENT)
    }

    companion object {
        /** [text] itself, every character plain. */
        fun literal(text: String, matchCase: Boolean): SearchPattern = compile(Pattern.quote(text), text, matchCase)

        /** [text] as a regular expression. @throws InvalidPatternException */
        fun regex(text: String, matchCase: Boolean): SearchPattern {
            if (text.length <= FileCaps.PATTERN_CHARS && repeatCopies(text) > FileCaps.PATTERN_REPEATS) {
                throw InvalidPatternException(PatternProblem.TOO_MANY_REPEATS)
            }
            return compile(text, text, matchCase)
        }

        private fun compile(expression: String, typed: String, matchCase: Boolean): SearchPattern {
            if (typed.length > FileCaps.PATTERN_CHARS) throw InvalidPatternException(PatternProblem.TOO_LONG)
            return try {
                SearchPattern(Pattern.compile(expression, if (matchCase) 0 else Pattern.CASE_INSENSITIVE))
            } catch (expected: PatternSyntaxException) {
                throw InvalidPatternException(PatternProblem.INVALID)
            }
        }

        /**
         * The copies [regex]'s counted repetitions make, ALL multiplied together — more than those nested in
         * one another, which is what RE2/J expands: `x{n}` makes n, `x{n,}` n + 1, `x{n,m}` m. An escaped
         * character and a character class are skipped; a `{` that opens no repetition is a plain character,
         * as RE2 reads it. Stops counting past the cap.
         */
        internal fun repeatCopies(regex: String): Long {
            var product = 1L
            var inClass = false
            var i = 0
            while (i < regex.length && product <= FileCaps.PATTERN_REPEATS) {
                val c = regex[i]
                when {
                    c == ESCAPE -> i++
                    inClass -> inClass = c != ']'
                    c == '[' -> {
                        inClass = true
                        // `[^]…]` and `[]…]`: a `]` first in a class is one of its characters.
                        if (regex.getOrNull(i + 1) == '^') i++
                        if (regex.getOrNull(i + 1) == ']') i++
                    }
                    c == '{' -> countedAt(regex, i)?.let { (copies, end) ->
                        product *= copies
                        i = end
                    }
                }
                i++
            }
            return product
        }

        /** The repetition `{n}`, `{n,}` or `{n,m}` at [start]: its copies, at least 1, and where its `}` is. */
        private fun countedAt(regex: String, start: Int): Pair<Long, Int>? {
            var i = start + 1
            val min = regex.digitsAt(i)?.also { i += it.length }?.toLong() ?: return null
            var copies = min
            if (regex.getOrNull(i) == ',') {
                i++
                val max = regex.digitsAt(i)
                copies = if (max == null) min + 1 else max.toLong()
                i += max?.length ?: 0
            }
            return if (regex.getOrNull(i) == '}') copies.coerceAtLeast(1) to i else null
        }

        /** The digits at [start], up to [MAX_DIGITS] of them (RE2 refuses a count over 1 000), or null. */
        private fun String.digitsAt(start: Int): String? {
            var end = start
            while (end < length && end - start < MAX_DIGITS && this[end].isAsciiDigit()) end++
            return if (end > start) substring(start, end) else null
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'

        private const val ESCAPE = '\\'
        private const val MAX_DIGITS = 6
    }
}

/**
 * [text] as the engine reads it: every [CHECK_EVERY] characters read, [check] runs. RE2/J reads its input
 * through `CharSequence.charAt` only — a `String` it would read the same way, this one is not copied.
 */
private class CheckedChars(private val text: CharSequence, private val check: () -> Unit) : CharSequence {
    private var reads = 0

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (++reads and CHECK_MASK == 0) check()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text.toString()

    private companion object {
        /** Every 4 096 characters read. */
        const val CHECK_MASK = 4095
    }
}

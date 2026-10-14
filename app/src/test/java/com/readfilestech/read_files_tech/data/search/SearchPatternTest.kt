package com.readfilestech.read_files_tech.data.search

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException

/** The patterns the user types: refused in words, matched in a time linear in the text, stopped when asked. */
class SearchPatternTest {
    private fun problemOf(block: () -> Unit): PatternProblem = assertThrows<InvalidPatternException> { block() }.problem

    @Test
    fun `a word is found as typed, its regex characters plain, case ignored unless asked`() {
        val pattern = SearchPattern.literal("a.b (c)", matchCase = false)
        assertThat(pattern.find("x A.B (C) y")).isEqualTo(2 until 9)
        assertThat(pattern.find("x aXb (c) y")).isNull()
        assertThat(SearchPattern.literal("Café", matchCase = true).find("café")).isNull()
        assertThat(SearchPattern.literal("CAFÉ", matchCase = false).find("un café")).isEqualTo(3 until 7)
    }

    @Test
    fun `a regex is matched, from a position`() {
        val pattern = SearchPattern.regex("""\d{3}""", matchCase = true)
        assertThat(pattern.find("ab 123 456")).isEqualTo(3 until 6)
        assertThat(pattern.find("ab 123 456", from = 4)).isEqualTo(7 until 10)
    }

    @Test
    fun `a pattern over 200 characters is refused, typed as a word or as a regex`() {
        val long = "a".repeat(FileCaps.PATTERN_CHARS + 1)
        assertThat(problemOf { SearchPattern.literal(long, matchCase = false) }).isEqualTo(PatternProblem.TOO_LONG)
        assertThat(problemOf { SearchPattern.regex(long, matchCase = false) }).isEqualTo(PatternProblem.TOO_LONG)
        SearchPattern.regex("a".repeat(FileCaps.PATTERN_CHARS), matchCase = false)
    }

    @Test
    fun `a syntax error, a look-around and a back-reference are refused, never thrown raw`() {
        listOf("(", "a(?=b)", """(a)\1""", "[z-a]").forEach { text ->
            assertThat(problemOf { SearchPattern.regex(text, matchCase = false) }).isEqualTo(PatternProblem.INVALID)
        }
    }

    @Test
    fun `counted repetitions making too many copies together are refused before compiling`() {
        // Side by side first: refused before RE2/J would expand the nested one below.
        assertThat(
            problemOf {
                SearchPattern.regex("a{10}b{200}", matchCase = false)
            }
        ).isEqualTo(PatternProblem.TOO_MANY_REPEATS)
        assertThat(
            problemOf { SearchPattern.regex("((a{1000}){1000}){1000}", matchCase = false) }
        ).isEqualTo(PatternProblem.TOO_MANY_REPEATS)
        // A date, a phone number: a few copies.
        SearchPattern.regex("""\d{4}-\d{2}-\d{2}""", matchCase = false)
        SearchPattern.regex("[0-9]{1,1000}", matchCase = false)
    }

    @Test
    fun `the copies are counted outside escapes and classes, a brace that opens no count is a character`() {
        assertThat(SearchPattern.repeatCopies("a{3}")).isEqualTo(3)
        assertThat(SearchPattern.repeatCopies("a{3,}")).isEqualTo(4)
        assertThat(SearchPattern.repeatCopies("a{2,5}(b{4})")).isEqualTo(20)
        assertThat(SearchPattern.repeatCopies("""\{999}[{999}]{x}""")).isEqualTo(1)
        assertThat(SearchPattern.repeatCopies("[]{9}]")).isEqualTo(1)
    }

    /** ICU, Android's engine, never ends on this; RE2/J reads the text once. */
    @Test
    fun `a catastrophic pattern ends at once on a long text`() {
        val pattern = SearchPattern.regex("(a+)+$", matchCase = true)
        val text = "a".repeat(100_000) + "!"
        val started = System.nanoTime()
        assertThat(pattern.find(text)).isNull()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000L)
    }

    @Test
    fun `the check runs while the engine reads a long text, and stops it`() {
        var checks = 0
        val text = "x".repeat(1_000_000)
        assertThrows<CancellationException> {
            SearchPattern.regex("y", matchCase = true).find(text) {
                if (++checks == 3) throw CancellationException("stopped")
            }
        }
        assertThat(checks).isEqualTo(3)
    }

    @Test
    fun `a replacement takes groups, and one naming a missing group is refused`() {
        val pattern = SearchPattern.regex("""IMG_(\d+)""", matchCase = true)
        assertThat(pattern.replaceAll("IMG_042", "Photo_$1")).isEqualTo("Photo_042")
        assertThat(pattern.replaceAll("IMG_042", """\$1""")).isEqualTo("$1")
        assertThat(problemOf { pattern.replaceAll("IMG_042", "x$2") }).isEqualTo(PatternProblem.BAD_REPLACEMENT)
        assertThat(problemOf { pattern.replaceAll("IMG_042", "x\${name}") }).isEqualTo(PatternProblem.BAD_REPLACEMENT)
    }
}

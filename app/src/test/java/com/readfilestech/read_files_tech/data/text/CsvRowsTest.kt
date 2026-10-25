package com.readfilestech.read_files_tech.data.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CsvRowsTest {
    @Test
    fun `plain fields, rows ended by every kind of line break`() {
        assertThat(CsvRows.parse("a,b\r\nc,d\ne,f\rg,h")).containsExactly(
            listOf("a", "b"),
            listOf("c", "d"),
            listOf("e", "f"),
            listOf("g", "h"),
        ).inOrder()
    }

    @Test
    fun `quoted fields hold separators, line breaks and doubled quotes`() {
        assertThat(CsvRows.parse("\"a,b\",\"line\nbreak\",\"say \"\"hi\"\"\"")).containsExactly(
            listOf("a,b", "line\nbreak", "say \"hi\""),
        )
    }

    @Test
    fun `empty fields and an empty quoted last field are kept`() {
        assertThat(CsvRows.parse("a,,c\n,\n\"\"")).containsExactly(
            listOf("a", "", "c"),
            listOf("", ""),
            listOf(""),
        ).inOrder()
    }

    @Test
    fun `nothing is dropped from malformed input`() {
        // A quote inside an unquoted field stays; an unterminated quoted field ends with the text.
        assertThat(CsvRows.parse("ab\"c,d\n\"open,end")).containsExactly(
            listOf("ab\"c", "d"),
            listOf("open,end"),
        ).inOrder()
    }

    @Test
    fun `stops at maxRows, and a trailing line break adds no row`() {
        assertThat(CsvRows.parse("1\n2\n3\n", maxRows = 2)).containsExactly(listOf("1"), listOf("2")).inOrder()
        assertThat(CsvRows.parse("1\n2\n")).hasSize(2)
        assertThat(CsvRows.parse("")).isEmpty()
        assertThat(CsvRows.parse("a;b", separator = ';')).containsExactly(listOf("a", "b"))
    }

    /**
     * What Excel 16 made of these lines, opened through COM on a French Windows (2026-10-12): the
     * oracle of the malformed cases. `csv` 8, the 2.15.3's package, read the first as `5" screen`.
     */
    @Test
    fun `malformed quotes are read as Excel reads them`() {
        assertThat(CsvRows.parse("\"5\" screen\";x", ';')).containsExactly(listOf("5 screen\"", "x"))
        assertThat(CsvRows.parse("\"ab\"cd;x", ';')).containsExactly(listOf("abcd", "x"))
        assertThat(CsvRows.parse("ab\"c;\"d\"\"e\";f", ';')).containsExactly(listOf("ab\"c", "d\"e", "f"))
        assertThat(CsvRows.parse("a;b\r\n\"open;never\r\nclosed;x\r\n", ';')).containsExactly(
            listOf("a", "b"),
            listOf("open;never\r\nclosed;x\r\n"),
        ).inOrder()
        // csv 8's own edge case: five quotes in a row, then the separator.
        assertThat(CsvRows.parse("\"1\",\"cnonce=\"\"\"\",nc=\"\"\"\"\",\"2\"")).containsExactly(listOf("1", "cnonce=\"\",nc=\"\"", "2"))
    }

    /** csv 8's `_detectDelimiter`: separators counted over ten lines, 2 more per line as full as the one before. */
    @Test
    fun `the separator is found as the 2_15_3 found it`() {
        assertThat(CsvRows.dialectOf("A;B;C")).isEqualTo(CsvDialect(';', 0))
        assertThat(CsvRows.dialectOf("A\tB\tC")).isEqualTo(CsvDialect('\t', 0))
        assertThat(CsvRows.dialectOf("a|b|c\n1|2|3")).isEqualTo(CsvDialect('|', 0))
        assertThat(CsvRows.dialectOf("A,b,c\nd,E,f")).isEqualTo(CsvDialect(',', 0))
        // A tie goes to the first of `,` `;` tab `|`; nothing found is `,`.
        assertThat(CsvRows.dialectOf("a,b;c").separator).isEqualTo(',')
        assertThat(CsvRows.dialectOf("").separator).isEqualTo(',')
        assertThat(CsvRows.dialectOf("one column\nonly").separator).isEqualTo(',')
        // Four commas in one line lose to three lines of one semicolon each: 3 + 2 + 2.
        assertThat(CsvRows.dialectOf("a;b\nc;d\ne;f,g,h,i,j").separator).isEqualTo(';')
        // Only ten lines are scored, an empty one among them: fifty lines of `;` after them change nothing.
        val late = "a,b\n".repeat(4) + "\n" + "a,b\n".repeat(5) + "a;b;c;d\n".repeat(50)
        assertThat(CsvRows.dialectOf(late).separator).isEqualTo(',')
    }

    /** Where the port departs from csv 8, each a file csv 8 split wrongly. */
    @Test
    fun `quoted separators do not count, and one absent from the first lines never wins`() {
        // csv 8 counted the four commas of the quoted text: 4 against 1 + 1 + 2, the tie to the comma.
        assertThat(CsvRows.dialectOf("titre;texte\n\"Bonjour, ceci, est, un, texte\";1").separator).isEqualTo(';')
        // A line break inside quotes ends no line: the second line has two separators, not one and one.
        assertThat(CsvRows.dialectOf("a;b;c\n\"x\ny\";2;3\n").separator).isEqualTo(';')
        // csv 8 took the first separator found ANYWHERE: this file of one column was split at line 12.
        val oneColumn = "name\n" + "someone\n".repeat(10) + "a|b\n"
        assertThat(CsvRows.dialectOf(oneColumn).separator).isEqualTo(',')
    }

    /** Excel's `sep=` first line: read, then skipped; a tab is a hint too (csv 8 trimmed it away). */
    @Test
    fun `an Excel sep= line names the separator, and the rows begin after it`() {
        assertThat(CsvRows.dialectOf("sep=;\r\nA;B;C")).isEqualTo(CsvDialect(';', 7))
        // No detection after the hint: commas everywhere, still `;`.
        assertThat(CsvRows.dialectOf("sep=;\nA,B,C")).isEqualTo(CsvDialect(';', 6))
        assertThat(CsvRows.dialectOf("sep=\t\nA\tB")).isEqualTo(CsvDialect('\t', 6))
        assertThat(CsvRows.dialectOf("sep= ; \nA;B")).isEqualTo(CsvDialect(';', 8))
        // Not a hint: a quote, two characters, nothing, no line break, a line too long — rows like any other.
        listOf("sep=\"\na,b", "sep=;;\na;b", "sep=\na;b", "sep=;", "sep=" + " ".repeat(20) + ";\na;b").forEach {
            assertThat(CsvRows.dialectOf(it).start).isEqualTo(0)
        }
        assertThat(CsvRows.dialectOf("sep=;;\na;b").separator).isEqualTo(';')
    }

    @Test
    fun `the scanner counts every field, and a record of empty fields has no content`() {
        val text = CharsText(";;;\n\"\";\"\"\nx\n\"\"\"\"\n")
        val scanner = CsvScanner(text, ';')
        assertThat(scanner.record(0)).isTrue()
        assertThat(scanner.fieldCount).isEqualTo(4)
        assertThat(scanner.hasContent).isFalse()
        scanner.record(scanner.end)
        assertThat(scanner.fieldCount).isEqualTo(2)
        assertThat(scanner.hasContent).isFalse()
        scanner.record(scanner.end)
        assertThat(scanner.hasContent).isTrue()
        // One escaped quote is content.
        scanner.record(scanner.end)
        assertThat(scanner.hasContent).isTrue()
        assertThat(scanner.end).isEqualTo(text.length)
        // A visitor stops the record where it says.
        val seen = mutableListOf<Int>()
        val complete = scanner.record(0) { index, _, _ ->
            seen += index
            index < 1
        }
        assertThat(complete).isFalse()
        assertThat(seen).containsExactly(0, 1).inOrder()
    }

    /** A field of a hostile file can be the whole file: only what is shown of it is decoded, whole characters only. */
    @Test
    fun `a field's text is cut at maxChars, never inside a character`() {
        val text = CharsText("\"" + "😀".repeat(10) + "\"")
        assertThat(text.field(0, text.length)).isEqualTo("😀".repeat(10))
        assertThat(text.field(0, text.length, maxChars = 5)).isEqualTo("😀😀")
        assertThat(text.field(0, text.length, maxChars = 4)).isEqualTo("😀😀")
        val quotes = CharsText("\"" + "\"\"".repeat(100) + "\"")
        assertThat(quotes.field(0, quotes.length, maxChars = 7)).isEqualTo("\"".repeat(7))
        assertThat(unquote("plain\"text")).isEqualTo("plain\"text")
        assertThat(unquote("\"a\"b\"c")).isEqualTo("ab\"c")
    }
}

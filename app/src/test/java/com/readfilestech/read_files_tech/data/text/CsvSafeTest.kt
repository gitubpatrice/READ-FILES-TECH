package com.readfilestech.read_files_tech.data.text

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * `test/csv_safe_test.dart` of the 2.15.3 (13 tests), ported. Its "leaves non-Strings alone" (int, double,
 * null) has no Kotlin counterpart: [CsvSafe.sanitizeCell] takes a String, the compiler refuses the rest.
 */
class CsvSafeTest {
    @Test
    fun `= + - @ get an apostrophe in front`() {
        assertThat(CsvSafe.sanitizeCell("=1+1")).isEqualTo("'=1+1")
        assertThat(CsvSafe.sanitizeCell("+CMD")).isEqualTo("'+CMD")
        assertThat(CsvSafe.sanitizeCell("-2+3")).isEqualTo("'-2+3")
        assertThat(CsvSafe.sanitizeCell("@SUM(A1:A9)")).isEqualTo("'@SUM(A1:A9)")
    }

    @Test
    fun `a tab or a carriage return before a formula does not hide it`() {
        assertThat(CsvSafe.sanitizeCell("\t=BAD")).isEqualTo("'\t=BAD")
        assertThat(CsvSafe.sanitizeCell("\r=BAD")).isEqualTo("'\r=BAD")
    }

    @Test
    fun `safe cells are left as they are`() {
        assertThat(CsvSafe.sanitizeCell("Hello")).isEqualTo("Hello")
        assertThat(CsvSafe.sanitizeCell("42")).isEqualTo("42")
        assertThat(CsvSafe.sanitizeCell("")).isEqualTo("")
        assertThat(CsvSafe.sanitizeCell("1=2")).isEqualTo("1=2")
    }

    @Test
    fun `encodeSafe guards each cell`() {
        val out = CsvSafe.encodeSafe(listOf(listOf("Nom", "Valeur"), listOf("Patrice", "=cmd|\"/c calc\"!A1"), listOf("Sécu", "OK")))
        assertThat(out).contains("'=cmd|")
        assertThat(out).contains("Patrice")
        assertThat(out).contains("Sécu")
    }

    @Test
    fun `encodeSafe of harmless rows reads back`() {
        val out = CsvSafe.encodeSafe(listOf(listOf("a", "b"), listOf("1", "2")))
        assertThat(out.split("\n").size).isAtLeast(2)
        assertThat(CsvRows.parse(out)).containsExactly(listOf("a", "b"), listOf("1", "2")).inOrder()
    }

    @Test
    fun `a formula after a space is guarded`() {
        assertThat(CsvSafe.sanitizeCell(" =HYPERLINK(\"http://x\")")).startsWith("'")
    }

    @Test
    fun `a formula after a line break is guarded`() {
        assertThat(CsvSafe.sanitizeCell("\n=cmd|calc")).startsWith("'")
    }

    @Test
    fun `a formula after mixed blanks is guarded`() {
        assertThat(CsvSafe.sanitizeCell("  \t\n @SUM(A1)")).startsWith("'")
    }

    @Test
    fun `a no-break space protects nothing more`() {
        assertThat(CsvSafe.sanitizeCell("\u00A0+1+1")).startsWith("'")
        assertThat(CsvSafe.sanitizeCell("\u2007=1")).startsWith("'")
    }

    @Test
    fun `the cell is kept whole, blanks included`() {
        assertThat(CsvSafe.sanitizeCell(" =A1")).isEqualTo("' =A1")
    }

    @Test
    fun `ordinary text after blanks is left as it is`() {
        assertThat(CsvSafe.sanitizeCell("  bonjour")).isEqualTo("  bonjour")
    }

    @Test
    fun `a cell of blanks only is left as it is`() {
        assertThat(CsvSafe.sanitizeCell("   ")).isEqualTo("   ")
        assertThat(CsvSafe.sanitizeCell("\n")).isEqualTo("\n")
    }

    @Test
    fun `a field is quoted when it needs to be, or when asked`() {
        assertThat(quotedField("a;b", ';')).isEqualTo("\"a;b\"")
        assertThat(quotedField("a;b", ',')).isEqualTo("a;b")
        assertThat(quotedField("dit \"oui\"", ',')).isEqualTo("\"dit \"\"oui\"\"\"")
        assertThat(quotedField("l1\r\nl2", ',')).isEqualTo("\"l1\r\nl2\"")
        assertThat(quotedField("plain", ',', quoted = true)).isEqualTo("\"plain\"")
        assertThat(quotedField("", ',', quoted = true)).isEqualTo("\"\"")
    }
}

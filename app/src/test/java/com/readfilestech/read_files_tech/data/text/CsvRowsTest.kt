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
}

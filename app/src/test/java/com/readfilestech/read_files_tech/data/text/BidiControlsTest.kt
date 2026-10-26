package com.readfilestech.read_files_tech.data.text

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.viewer.zip.ZipPaths
import org.junit.jupiter.api.Test

/** A name from a file never shows another extension than its own (security audit of the 3.0, A-2). */
class BidiControlsTest {
    @Test
    fun `a right-to-left override shows as what it is, the real extension last`() {
        val name = "Facture\u202Efdp.apk"
        assertThat(name.withoutBidiControls()).isEqualTo("Facture\uFFFDfdp.apk")
        assertThat(name.withoutBidiControls()).endsWith(".apk")
    }

    @Test
    fun `every embedding, override, isolate and mark goes, nothing else`() {
        val embeddingsAndOverrides = listOf('\u202A', '\u202B', '\u202C', '\u202D', '\u202E')
        val isolatesAndMarks = listOf('\u2066', '\u2067', '\u2068', '\u2069', '\u200E', '\u200F', '\u061C')
        (embeddingsAndOverrides + isolatesAndMarks).forEach {
            assertThat("a${it}b".withoutBidiControls()).isEqualTo("a\uFFFDb")
        }
        val plain = "Rapport été 2026 — مرحبا שלום.pdf"
        assertThat(plain.withoutBidiControls()).isSameInstanceAs(plain)
    }

    @Test
    fun `an archive's entry is shown without its bidirectional controls, nor its line breaks`() {
        assertThat(ZipPaths.display("x\u202Efdp.apk")).isEqualTo("x\uFFFDfdp.apk")
        assertThat(ZipPaths.display("a\nb")).isEqualTo("a\uFFFDb")
    }
}

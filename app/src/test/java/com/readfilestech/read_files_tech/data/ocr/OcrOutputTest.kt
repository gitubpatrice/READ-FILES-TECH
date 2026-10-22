package com.readfilestech.read_files_tech.data.ocr

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.CancellationException

class OcrOutputTest {
    @TempDir
    lateinit var tmp: File

    private fun storage() = OutputStorage(
        FlutterPreferences(InMemorySharedPreferences()),
        { File(tmp, "emulated0") },
        { null },
        File(tmp, "app_flutter"),
    ) { LocalDateTime.of(2026, 10, 22, 9, 5, 7) }

    private val ocrFolder get() = File(tmp, "emulated0/Files Tech/OCR")

    @Test
    fun `the text goes to OCR, named as the 2_15_3 names it, in UTF-8 without a BOM, byte for byte`() {
        val text = "Première ligne — été\nSecond line 😀\r\nTroisième"
        val file = OcrOutput.save(storage(), text) {}
        assertThat(file).isEqualTo(File(ocrFolder, "ocr_20261022_090507.txt"))
        assertThat(file.readBytes()).isEqualTo(text.toByteArray(Charsets.UTF_8))
        // A second save never replaces the first.
        val second = OcrOutput.save(storage(), "other") {}
        assertThat(second.name).isEqualTo("ocr_20261022_090507_1.txt")
        assertThat(file.readText()).isEqualTo(text)
    }

    @Test
    fun `a save stopped once the text is written leaves nothing in OCR`() {
        assertThrows<CancellationException> {
            OcrOutput.save(storage(), "x".repeat(200_000)) { throw CancellationException("screen left") }
        }
        assertThat(ocrFolder.list().orEmpty().toList()).isEmpty()
    }
}

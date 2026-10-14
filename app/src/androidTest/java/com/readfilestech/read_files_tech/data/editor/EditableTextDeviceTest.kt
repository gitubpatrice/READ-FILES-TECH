package com.readfilestech.read_files_tech.data.editor

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The code editor's bytes on Android's own charsets (ICU), which the JVM tests never run. An ICU encoder
 * may hold a surrogate left alone at the very end of the text and give it to `flush` only (Claude review
 * R22): such a text is refused in each encoding a file can have, and a text with its pairs whole is
 * written as the JVM writes it.
 */
class EditableTextDeviceTest {
    private val files = listOf(
        "utf-8" to "a\n".toByteArray(Charsets.UTF_8),
        "utf-8 with BOM" to BOM_UTF8 + "a\n".toByteArray(Charsets.UTF_8),
        "utf-16 LE" to BOM_LE + "a\n".toByteArray(Charsets.UTF_16LE),
        "utf-16 BE" to BOM_BE + "a\n".toByteArray(Charsets.UTF_16BE),
        "windows-1252" to "é\n".toByteArray(charset("windows-1252")),
    )

    @Test
    fun aSurrogateWithoutItsPairIsRefusedInEveryEncoding() {
        for ((label, bytes) in files) {
            val text = EditableText.of(bytes)
            for (edited in listOf("a\n\uD83D", "a\n\uDE00", "\uD83D", "a\uD83D\n")) {
                assertThrows("$label, ${edited.length} chars", UnencodableTextException::class.java) {
                    runBlocking { text.encode(edited) }
                }
            }
        }
    }

    @Test
    fun aTextWithItsPairsWholeIsWrittenAsTheJvmWritesIt() {
        val emoji = String(Character.toChars(0x1F600))
        val utf8 = runBlocking { EditableText.of("a\n".toByteArray(Charsets.UTF_8)).encode("a\n$emoji") }
        assertThat(utf8.bytes).isEqualTo("a\n$emoji".toByteArray(Charsets.UTF_8))
        val utf16 = runBlocking { EditableText.of(BOM_LE + "a\n".toByteArray(Charsets.UTF_16LE)).encode("a\n$emoji") }
        assertThat(utf16.bytes).isEqualTo(BOM_LE + "a\n$emoji".toByteArray(Charsets.UTF_16LE))
    }

    private companion object {
        val BOM_UTF8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val BOM_LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val BOM_BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
    }
}

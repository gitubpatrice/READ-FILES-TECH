package com.readfilestech.read_files_tech.data.tools

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.convert.SelectionTooLargeException
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

/** Create a ZIP: names safe and unique, every byte of every file, the cap before anything is written. */
class ZipCreationTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var sources: File
    private lateinit var primary: File

    @BeforeEach
    fun setUp() {
        sources = File(tmp, "sources").apply { mkdirs() }
        primary = File(tmp, "emulated0").apply { mkdirs() }
    }

    private fun source(path: String, bytes: ByteArray) = File(sources, path).apply {
        parentFile.mkdirs()
        writeBytes(bytes)
    }

    private fun entriesOf(zip: ByteArray): Map<String, ByteArray> = ZipInputStream(zip.inputStream()).use { input ->
        generateSequence { input.nextEntry }.associate { it.name to input.readBytes() }
    }

    private fun zipOf(files: List<File>, cap: Long = 200L * 1024 * 1024): ByteArray =
        ByteArrayOutputStream().also { ZipCreation.write(files, it, cap, {}) {} }.toByteArray()

    private fun storage() = OutputStorage(FlutterPreferences(InMemorySharedPreferences()), {
        primary
    }, { null }, File(tmp, "private")) {
        LocalDateTime.of(2026, 10, 14, 9, 30, 0)
    }

    private fun conversions() = File(primary, "Files Tech/Conversions").listFiles().orEmpty().map(File::getName)

    /** F24: two files named alike in two folders made two entries of one name. */
    @Test
    fun `names are unique, case ignored, and keep their extension`() {
        assertThat(ZipCreation.entryNames(listOf("notes.txt", "NOTES.TXT", "notes.txt", "notes (1).txt", ".bashrc", ".bashrc")))
            .containsExactly("notes.txt", "NOTES (1).TXT", "notes (2).txt", "notes (1) (1).txt", ".bashrc", ".bashrc (1)").inOrder()
    }

    @Test
    fun `a backslash and control characters never reach a name`() {
        assertThat(ZipCreation.entryNames(listOf("..\\..\\evil.txt", "tab\there.txt", "del\u007F.txt")))
            .containsExactly(".._.._evil.txt", "tab_here.txt", "del_.txt").inOrder()
    }

    @Test
    fun `every file is in the archive, byte for byte, under its name`() {
        val binary = ByteArray(300_000) { (it * 31 + 7).toByte() }
        val files = listOf(
            source("a/report.pdf", binary),
            source("b/report.pdf", "second".toByteArray()),
            source("empty.txt", ByteArray(0)),
            source("Straße é.txt", "unicode".toByteArray()),
        )
        val entries = entriesOf(zipOf(files))
        assertThat(entries.keys).containsExactly("report.pdf", "report (1).pdf", "empty.txt", "Straße é.txt")
        assertThat(entries.getValue("report.pdf")).isEqualTo(binary)
        assertThat(entries.getValue("report (1).pdf")).isEqualTo("second".toByteArray())
        assertThat(entries.getValue("empty.txt")).isEmpty()
    }

    @Test
    fun `the archive does not close the stream it writes to`() {
        var closed = false
        val out = object : OutputStream() {
            override fun write(b: Int) = Unit

            override fun close() {
                closed = true
            }
        }
        ZipCreation.write(listOf(source("a.txt", "x".toByteArray())), out, 1_000, {}) {}
        assertThat(closed).isFalse()
    }

    @Test
    fun `files too large together are refused before anything is written`() {
        val files = listOf(source("a.bin", ByteArray(600)), source("b.bin", ByteArray(600)))
        val refused = assertThrows<SelectionTooLargeException> { ZipCreation.checkTotal(files, cap = 1_000) }
        assertThat(refused.capBytes).isEqualTo(1_000)
        ZipCreation.checkTotal(files, cap = 1_200)
    }

    @Test
    fun `a file grown past the cap while being read stops the archive`() {
        val files = listOf(source("a.bin", ByteArray(600)), source("b.bin", ByteArray(600)))
        assertThrows<SelectionTooLargeException> { zipOf(files, cap = 1_000) }
    }

    @Test
    fun `a file that cannot be read is named`() {
        val missing = File(sources, "gone.txt")
        val error = assertThrows<ZipSourceException> { zipOf(listOf(source("a.txt", "x".toByteArray()), missing)) }
        assertThat(error.name).isEqualTo("gone.txt")
    }

    @Test
    fun `the output is Conversions archive with its stamp, written whole`() {
        val files = listOf(source("a.txt", "alpha".toByteArray()), source("b.txt", "beta".toByteArray()))
        var last = 0f
        val archive = runBlocking { ZipCreator(storage()).create(files) { last = it } }
        assertThat(archive.name).isEqualTo("archive_20261014_093000.zip")
        assertThat(
            entriesOf(archive.readBytes()).mapValues {
                String(it.value)
            }
        ).containsExactly("a.txt", "alpha", "b.txt", "beta")
        assertThat(last).isEqualTo(1f)
        assertThat(conversions()).containsExactly("archive_20261014_093000.zip")
    }

    @Test
    fun `a failure or a stop leaves nothing in Conversions`() {
        val good = source("a.txt", "alpha".toByteArray())
        assertThrows<ZipSourceException> {
            runBlocking { ZipCreator(storage()).create(listOf(good, File(sources, "gone.txt"))) {} }
        }
        assertThat(conversions()).isEmpty()
        assertThrows<CancellationException> {
            runBlocking { ZipCreator(storage()).create(listOf(good)) { throw CancellationException("stopped") } }
        }
        assertThat(conversions()).isEmpty()
    }

    @Test
    fun `too large together, not even a reservation is made`() {
        val big = source("big.bin", ByteArray(1)).apply {
            java.io.RandomAccessFile(this, "rw").use { it.setLength(201L * 1024 * 1024) }
        }
        assertThrows<SelectionTooLargeException> { runBlocking { ZipCreator(storage()).create(listOf(big)) {} } }
        assertThat(File(primary, "Files Tech").exists()).isFalse()
    }
}

package com.readfilestech.read_files_tech.ui.tools.scanner

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.scanner.JpegInfo
import com.readfilestech.read_files_tech.data.scanner.JpegPage
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.util.concurrent.CancellationException
import javax.imageio.ImageIO

/** The scanner's export: one PDF in `Scans/`, nothing at all when the scanner is left while it is written. */
class ScanExportTest {
    @TempDir
    lateinit var tmp: File

    private fun storage() = OutputStorage(
        FlutterPreferences(InMemorySharedPreferences()),
        { File(tmp, "emulated0") },
        { null },
        File(tmp, "app_flutter"),
    ) { LocalDateTime.of(2026, 10, 22, 9, 5, 7) }

    private val scans get() = File(tmp, "emulated0/Files Tech/Scans")

    private val jpeg: ByteArray = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB), "jpg", it)
    }.toByteArray()

    private fun page(open: () -> InputStream = { ByteArrayInputStream(jpeg) }) =
        JpegPage(JpegInfo.read(ByteArrayInputStream(jpeg)), jpeg.size.toLong(), open)

    @Test
    fun `the PDF goes to Scans, under the 2_15_3's name`() {
        val pdf = runBlocking { storage().saveScan(listOf(page(), page())) }
        assertThat(pdf).isEqualTo(File(scans, "scan_20261022_090507.pdf"))
        assertThat(pdf.readBytes().copyOf(5).decodeToString()).isEqualTo("%PDF-")
    }

    /** It wrote and published the PDF: the JPEG pages are copied without any check for a stop. */
    @Test
    fun `an export stopped while its last page is copied publishes nothing`() {
        val storage = storage()
        assertThrows<CancellationException> {
            runBlocking {
                val job = coroutineContext.job
                val last = page {
                    // The scanner left while the last page is copied into the PDF.
                    job.cancel()
                    ByteArrayInputStream(jpeg)
                }
                storage.saveScan(listOf(page(), last))
            }
        }
        assertThat(scans.list().orEmpty().toList()).isEmpty()
    }
}

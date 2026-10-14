package com.readfilestech.read_files_tech.data.convert

import android.content.Context
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.csv.CsvTable
import com.readfilestech.read_files_tech.data.viewer.document.DocumentText
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfConnection
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/** The conversions of the Convert tool, in the 2.15.3's order; each makes one file of [extension] in `Conversions/`. */
enum class Conversion(val extension: String) {
    IMAGES_TO_PDF("pdf"),
    CSV_TO_XLSX("xlsx"),
    TEXT_TO_PDF("pdf"),
    PDF_TO_TEXT("txt"),
    DOCX_TO_TEXT("txt"),
    IMAGE_TO_JPG("jpg"),
    IMAGE_TO_PNG("png"),
}

/** What a conversion came to, when it did not fail. */
sealed interface ConversionResult {
    /** The output, complete, in `Conversions/`. */
    class Made(val file: File) : ConversionResult

    /** PDF → TXT of a file under a password; [wrong] after one that was not it. Nothing was written. */
    data class PasswordRequired(val wrong: Boolean) : ConversionResult
}

/**
 * The Convert tool's work (`ConvertScreen` of the 2.15.3): reads the file chosen within its caps, writes
 * the output into a file reserved in `Conversions/` ([OutputStorage]), atomically — a failure or a
 * cancellation leaves nothing there, not even the empty reservation the 2.15.x left (D18). Blocking: run it
 * off the main thread. A PDF is parsed or written by PdfBox in the `:pdf` process only (docs D-029).
 */
class Converter @Inject constructor(@param:ApplicationContext private val app: Context, private val outputs: OutputStorage) {
    /**
     * [conversion] of [files] — several for Images → PDF, one for the others. [password] is the user's for
     * PDF → TXT, null at first. [onProgress] gets the part done, from 0 to 1, when it is known.
     */
    suspend fun convert(conversion: Conversion, files: List<File>, password: String?, onProgress: (Float) -> Unit): ConversionResult {
        val job = currentCoroutineContext()
        // Called between two pieces of work, in the output's writing too: a cancellation stops it there.
        val progress = { done: Float ->
            job.ensureActive()
            onProgress(done)
        }
        val file = files.first()
        return when (conversion) {
            Conversion.IMAGES_TO_PDF -> imagesToPdf(files, job, progress)
            Conversion.PDF_TO_TEXT -> pdfToText(file, password, progress)
            else -> ConversionResult.Made(single(conversion, file, progress))
        }
    }

    private suspend fun single(conversion: Conversion, file: File, progress: (Float) -> Unit): File {
        val name = file.nameWithoutExtension
        return when (conversion) {
            Conversion.CSV_TO_XLSX -> {
                val table = CsvTable.read(StorageFileSource(file))
                val records = table.rows + 1f
                output(conversion, name) { CsvToXlsx.write(table, it) { done -> progress(done / records) } }
            }
            Conversion.TEXT_TO_PDF -> {
                val text = readText(file)
                output(conversion, name) { TextPdf.write(text, it) { done -> progress(done.toFloat() / text.rowCount) } }
            }
            Conversion.DOCX_TO_TEXT -> {
                val document = DocumentText.read(StorageFileSource(file))
                if (!document.hasText) throw NothingToConvertException("a document without text")
                output(conversion, name) { DocumentToText.write(document.document, it) }
            }
            Conversion.IMAGE_TO_JPG -> output(conversion, name) { ImageConversions.write(file, ImageFormat.JPG, it) }
            else -> output(conversion, name) { ImageConversions.write(file, ImageFormat.PNG, it) }
        }
    }

    /** The 2.15.3 named the PDF `images` (`convert_screen.dart:121`): it is made of several files. */
    private suspend fun imagesToPdf(files: List<File>, job: CoroutineContext, progress: (Float) -> Unit): ConversionResult {
        ImageConversions.checkSizes(files)
        return PdfConnection(app).use { connection ->
            connection.ready()
            connection.newImagesPdf().use { document ->
                files.forEachIndexed { index, image ->
                    job.ensureActive()
                    ImageConversions.addPage(document, image)
                    progress((index + 1f) / files.size)
                }
                ConversionResult.Made(output(Conversion.IMAGES_TO_PDF, IMAGES_NAME) { document.save(it) })
            }
        }
    }

    /**
     * The empty password first — a file locked by an owner password only opens without asking, as in the
     * viewer — then the user's. The 2.15.3 refused every encrypted PDF without asking (RAPPORT-PDF §6).
     */
    private suspend fun pdfToText(file: File, password: String?, progress: (Float) -> Unit): ConversionResult =
        StorageFileSource(file).acquireFile(FileCaps.PDF_VIEWER).use { acquired ->
            PdfConnection(app).use { connection ->
                connection.ready()
                val document = connection.openText(acquired.file, password.orEmpty())
                document?.use { pdf ->
                    ConversionResult.Made(output(Conversion.PDF_TO_TEXT, file.nameWithoutExtension) { writePages(pdf, it, progress) })
                } ?: ConversionResult.PasswordRequired(wrong = password != null)
            }
        }

    /** Every page, in order, as [PdfTextOutput] lays them out; nothing written when there is no text to give. */
    private fun writePages(pdf: PdfTextDocument, out: OutputStream, progress: (Float) -> Unit) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        val text = PdfTextOutput(writer)
        for (index in 0 until pdf.pageCount) {
            text.startPage(index + 1)
            pdf.page(index, text::text)
            progress((index + 1f) / pdf.pageCount)
        }
        writer.flush()
        if (!text.hasText) throw NothingToConvertException("no selectable text: a scanned PDF")
    }

    /** The text file within [FileCaps.TEXT_TO_PDF], decoded as the text viewer decodes it. */
    private suspend fun readText(file: File): TextDocument {
        val source = StorageFileSource(file)
        val (bytes, size) = source.open(FileCaps.TEXT_TO_PDF).use { input ->
            TextDocument.readAll(input, source.length(), FileCaps.TEXT_TO_PDF)
        }
        return TextDocument.of(bytes, size)
    }

    /** A new file of `Conversions/` filled by [write], atomically; given back if anything fails. */
    private fun output(conversion: Conversion, name: String, write: (OutputStream) -> Unit): File {
        val reserved = outputs.reserve(OutputCategory.CONVERSIONS, name, conversion.extension)
        var written = false
        try {
            outputs.write(reserved, write)
            written = true
        } finally {
            if (!written) outputs.discard(reserved)
        }
        return reserved
    }

    private companion object {
        const val IMAGES_NAME = "images"
    }
}

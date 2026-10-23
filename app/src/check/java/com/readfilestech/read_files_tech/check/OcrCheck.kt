package com.readfilestech.read_files_tech.check

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.core.graphics.scale
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.images.ImageTooLargeException
import com.readfilestech.read_files_tech.data.ocr.OcrEngine
import com.readfilestech.read_files_tech.data.ocr.OcrImages
import com.readfilestech.read_files_tech.data.ocr.OcrModels
import com.readfilestech.read_files_tech.data.ocr.RecognitionFailedException
import com.readfilestech.read_files_tech.data.scanner.PageFilter
import com.readfilestech.read_files_tech.data.scanner.PageGeometry
import com.readfilestech.read_files_tech.data.scanner.PageSettings
import com.readfilestech.read_files_tech.data.scanner.ScanPages
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * The OCR's oracle on a device (phase 5, `tools/oracles/ocr_oracle.py`): each image of a folder the script
 * pushed goes through the screen's own calls — [OcrImages.decode], then [OcrEngine.recognize] — in the
 * `check` build, the release as R8 makes it, so that what R8 keeps of Tesseract's binding is what is
 * measured. The report gives, per image, the outcome, the text found, the size decoded and the times; the
 * script judges the text.
 *
 * What the app does is the default. The script may ask, to MEASURE what the app does not (docs D-032):
 * `lang` and `tessdata` (other trained data, pushed by the script: a folder holding `tessdata/`), `psm`
 * (another page segmentation mode), `scale` (the decoded picture resized by this factor), `scan` (the
 * picture first made a page by the scanner's own code: its document found, straightened, white paper).
 */
class OcrCheck(private val context: Context, private val options: Bundle?) {
    suspend fun run(dir: File): Map<String, Any?> {
        val external = options?.getString(TESSDATA)?.let(::File)
        val engine = OcrEngine(
            dataPath = { external ?: OcrModels.prepare(context) },
            languages = options?.getString(LANG) ?: OcrModels.LANGUAGES,
            pageSegMode = options?.getInt(PSM, OcrEngine.PAGE_SEG_MODE) ?: OcrEngine.PAGE_SEG_MODE,
        )
        return engine.use {
            val images = dir.listFiles { f -> f.isFile && f.name != MANIFEST }.orEmpty().sortedBy { it.name }
            val start = SystemClock.elapsedRealtime()
            // The trained data checked (and copied the first time) before the first picture, timed apart.
            val prepared = loadOrFailure { if (external == null) OcrModels.prepare(context) }
            linkedMapOf(
                "prepareMillis" to SystemClock.elapsedRealtime() - start,
                "prepared" to prepared.exceptionOrNull()?.javaClass?.name,
                "results" to images.map { check(engine, it) },
            )
        }
    }

    private suspend fun check(engine: OcrEngine, file: File): Map<String, Any?> {
        val start = SystemClock.elapsedRealtime()
        var decodedAt = start
        var size: String? = null
        val result = loadOrFailure {
            val picture = sized(prepared(file))
            decodedAt = SystemClock.elapsedRealtime()
            size = "${picture.width}x${picture.height}"
            engine.recognize(picture)
        }
        val end = SystemClock.elapsedRealtime()
        val failure = result.exceptionOrNull()
        return linkedMapOf(
            "file" to file.name,
            "outcome" to (failure?.let(::outcomeOf) ?: OK),
            "detail" to failure?.let { "${it.javaClass.name}: ${it.cause?.javaClass?.name}" },
            "text" to result.getOrNull(),
            "decoded" to size,
            "decodeMillis" to if (size != null) decodedAt - start else null,
            "recognizeMillis" to if (size != null && failure == null) end - decodedAt else null,
        )
    }

    /** The picture as the screen decodes it, or first made a page by the scanner (`scan`). */
    private fun prepared(file: File): Bitmap {
        if (options?.getBoolean(SCAN) != true) return OcrImages.decode(file)
        val quad = ScanPages.find(file)?.takeIf(PageGeometry::isUsable) ?: PageGeometry.WHOLE
        val page = File(context.cacheDir, "ocr-check-page.jpg")
        try {
            ScanPages.render(file, PageSettings(quad, 0, PageFilter.WHITE_PAPER), page)
            return OcrImages.decode(page)
        } finally {
            page.delete()
        }
    }

    private fun sized(picture: Bitmap): Bitmap {
        val factor = options?.getFloat(SCALE, 1f) ?: 1f
        if (factor == 1f) return picture
        return picture.scale((picture.width * factor).roundToInt(), (picture.height * factor).roundToInt())
    }

    private fun outcomeOf(error: Throwable): String = when (error) {
        is OutOfMemoryError -> "out_of_memory"
        is FileTooLargeException -> "too_large"
        is ImageTooLargeException -> "too_many_pixels"
        is UnreadableFileException -> "unreadable"
        is RecognitionFailedException -> "recognition_failed"
        is IOException -> "cannot_open"
        else -> "unreadable"
    }

    private companion object {
        const val OK = "ok"
        const val MANIFEST = "ocr.json"
        const val LANG = "lang"
        const val TESSDATA = "tessdata"
        const val PSM = "psm"
        const val SCALE = "scale"
        const val SCAN = "scan"
    }
}

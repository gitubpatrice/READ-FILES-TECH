package com.readfilestech.read_files_tech.check

import android.content.Context
import android.os.SystemClock
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.images.ImageTooLargeException
import com.readfilestech.read_files_tech.data.ocr.OcrEngine
import com.readfilestech.read_files_tech.data.ocr.OcrImages
import com.readfilestech.read_files_tech.data.ocr.RecognitionFailedException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import java.io.File
import java.io.IOException

/**
 * The OCR's oracle on a device (phase 5, `tools/oracles/ocr_oracle.py`): each image of a folder the script
 * pushed goes through the screen's own calls — [OcrImages.decode], then [OcrEngine.recognize] — in the
 * `check` build, the release as R8 makes it, so that what R8 keeps of ML Kit is what is measured. The report
 * gives, per image, the outcome, the text found, the size decoded and the times; the script judges the text.
 */
class OcrCheck(private val context: Context) {
    suspend fun run(dir: File): Map<String, Any?> = OcrEngine(context).use { engine ->
        val images = dir.listFiles { f -> f.isFile && f.name != MANIFEST }.orEmpty().sortedBy { it.name }
        linkedMapOf("results" to images.map { check(engine, it) })
    }

    private suspend fun check(engine: OcrEngine, file: File): Map<String, Any?> {
        val start = SystemClock.elapsedRealtime()
        var decodedAt = start
        var size: String? = null
        val result = loadOrFailure {
            val picture = OcrImages.decode(file)
            decodedAt = SystemClock.elapsedRealtime()
            size = "${picture.width}x${picture.height}"
            engine.recognize(picture)
        }
        val end = SystemClock.elapsedRealtime()
        val failure = result.exceptionOrNull()
        return linkedMapOf(
            "file" to file.name,
            "outcome" to (failure?.let(::outcomeOf) ?: OK),
            "detail" to failure?.javaClass?.name,
            "text" to result.getOrNull(),
            "decoded" to size,
            "decodeMillis" to if (size != null) decodedAt - start else null,
            "recognizeMillis" to if (size != null && failure == null) end - decodedAt else null,
        )
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
    }
}

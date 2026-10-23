package com.readfilestech.read_files_tech.data.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Tesseract failed on a picture it was given (its data, its engine): never a fault of the file, which decoded. */
class RecognitionFailedException(cause: Throwable?) : Exception("text recognition failed", cause)

/**
 * Tesseract's text recognition (docs D-032), on the phone, offline: its trained data is the APK's own
 * ([OcrModels]), nothing is downloaded and nothing is sent. It replaces ML Kit's latin recognizer, the
 * 2.15.3's `TextRecognizer(script: TextRecognitionScript.latin)` (`ocr_screen.dart:22`), which reported each
 * use through Google Play services.
 *
 * One per screen, [close]d with it. Tesseract runs on a thread of its own, one picture at a time, and is
 * STOPPED when the screen goes: a recognition whose coroutine is cancelled is stopped by Tesseract's own
 * monitor at its next word, then the engine is recycled once it has returned. `stop()` is called from
 * Tesseract's progress callback, on Tesseract's thread — the binding's `stop()` resets the very state its
 * callback is reading, so called from another thread it could pull it from under a callback under way.
 */
class OcrEngine internal constructor(
    private val dataPath: suspend () -> File,
    private val languages: String,
    private val pageSegMode: Int,
) : AutoCloseable {
    constructor(context: Context) : this(
        dataPath = { OcrModels.prepare(context.applicationContext) },
        languages = OcrModels.LANGUAGES,
        pageSegMode = PAGE_SEG_MODE,
    )

    private val worker = Executors.newSingleThreadExecutor { Thread(it, THREAD) }
    private val thread = worker.asCoroutineDispatcher()

    /** Tesseract, opened at the first picture; touched on [worker] only. */
    private var api: TessBaseAPI? = null

    /** The recognition under way, for the progress callback; null between two. */
    @Volatile
    private var running: Job? = null

    @Volatile
    private var closed = false

    /**
     * The text of the upright [picture], as Tesseract gives it (`GetUTF8Text`): its blocks in its reading
     * order, a line break between lines, an empty line between blocks; empty when it finds none. Cancelled,
     * the recognition stops at its next word and its result is dropped.
     *
     * @throws RecognitionFailedException Tesseract could not start or failed.
     * @throws java.io.IOException its trained data could not be copied ([OcrModels.prepare]).
     */
    suspend fun recognize(picture: Bitmap): String {
        check(!closed) { "engine closed" }
        return withContext(thread) {
            val job = coroutineContext.job
            val tess = api ?: open().also { api = it }
            job.ensureActive()
            try {
                val recognised = recognised(tess, picture, job)
                job.ensureActive()
                if (!recognised) throw RecognitionFailedException(null)
                tess.getUTF8Text().orEmpty()
            } finally {
                tess.clear()
            }
        }
    }

    /**
     * Whether Tesseract recognised [picture] to its end: under a monitor that stop() cancels, which
     * getUTF8Text alone is not — the text is then read from the results, without a second recognition.
     * False when it was stopped.
     */
    // TooGenericExceptionCaught: the binding throws a bare RuntimeException for a picture it cannot take
    // ("Failed to read bitmap"); whatever it throws is the engine's failure, never the file's.
    @Suppress("TooGenericExceptionCaught")
    private fun recognised(tess: TessBaseAPI, picture: Bitmap, job: Job): Boolean = try {
        tess.setImage(picture)
        running = job
        tess.getHOCRText(0) != null
    } catch (failure: RuntimeException) {
        throw RecognitionFailedException(failure)
    } finally {
        running = null
    }

    private suspend fun open(): TessBaseAPI {
        val path = try {
            dataPath().path
        } catch (mismatch: ModelMismatchException) {
            // The APK's own data, not the picture: said as the engine's failure.
            throw RecognitionFailedException(mismatch)
        }
        val tess = TessBaseAPI { stopIfLeft() }
        // An unusable data path is an IllegalArgumentException; trained data Tesseract refuses, false.
        val started = runCatching { tess.init(path, languages, TessBaseAPI.OEM_LSTM_ONLY) }
        if (!started.getOrDefault(false)) {
            tess.recycle()
            throw RecognitionFailedException(started.exceptionOrNull())
        }
        tess.setPageSegMode(pageSegMode)
        return tess
    }

    /** Tesseract's progress callback, on [worker], inside a recognition: the screen gone, it stops. */
    private fun stopIfLeft() {
        if (closed || running?.isActive == false) api?.stop()
    }

    /** A recognition under way stops at its next word; Tesseract is recycled once it has returned. */
    override fun close() {
        if (closed) return
        closed = true
        worker.execute {
            api?.recycle()
            api = null
        }
        worker.shutdown()
    }

    internal companion object {
        /**
         * Fully automatic page segmentation, without orientation detection (Tesseract's default). Measured on
         * the oracle against a single uniform block (`PSM_SINGLE_BLOCK`): the same words on every page, the
         * block 5 % faster — but its pages are one column, and a single block reads two columns line across
         * line. Not the scanner's page detection and white paper first either: on the photo-like page, the
         * same words, and one second more before Tesseract starts.
         */
        const val PAGE_SEG_MODE = TessBaseAPI.PageSegMode.PSM_AUTO

        private const val THREAD = "ocr"
    }
}

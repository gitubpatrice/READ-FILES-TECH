package com.readfilestech.read_files_tech.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Tesseract on the device (docs D-032), through the app's engine: it reads, and leaving STOPS it — a
 * recognition cancelled, or its engine closed, returns at Tesseract's next word, not at the end of the page.
 * Timed against the same page read whole, on the same device: the stop must come well before.
 */
class OcrEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theTextDrawnOnAPictureIsRead() {
        runBlocking {
            OcrEngine(context).use { engine ->
                val text = engine.recognize(page(LINES, LINES.size))
                assertThat(text).contains("The quick brown fox jumps over the lazy dog")
                assertThat(text).contains("Nothing in this picture leaves the phone")
            }
        }
    }

    @Test
    fun aRecognitionCancelledStopsAtItsNextWordAndTheEngineReadsOn() {
        runBlocking {
            OcrEngine(context).use { engine ->
                engine.recognize(page(LINES, 2))
                val dense = page(LINES, DENSE_LINES)
                val whole = timed { engine.recognize(dense) }
                val stopped = timed {
                    val reading = async(Dispatchers.Default) { engine.recognize(dense) }
                    delay(whole / CANCEL_AT)
                    reading.cancel()
                    assertThat(runCatching { reading.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
                }
                Log.i(TAG, "whole page $whole ms, cancelled at ${whole / CANCEL_AT} ms, returned at $stopped ms")
                assertStoppedEarly(stopped, whole)
                // Stopped, not broken: the next picture is read.
                assertThat(engine.recognize(page(LINES, 2))).contains("The quick brown fox")
            }
        }
    }

    @Test
    fun anEngineClosedStopsTheRecognitionUnderWay() {
        runBlocking {
            val dense = page(LINES, DENSE_LINES)
            val whole = OcrEngine(context).use { engine ->
                engine.recognize(page(LINES, 2))
                timed { engine.recognize(dense) }
            }
            val engine = OcrEngine(context)
            engine.recognize(page(LINES, 2))
            val stopped = timed {
                val reading = async(Dispatchers.Default) { runCatching { engine.recognize(dense) } }
                delay(whole / CANCEL_AT)
                engine.close()
                // The screen gone: what it was reading is dropped, said as the engine's own failure.
                assertThat(reading.await().exceptionOrNull()).isInstanceOf(RecognitionFailedException::class.java)
            }
            Log.i(TAG, "whole page $whole ms, closed at ${whole / CANCEL_AT} ms, returned at $stopped ms")
            assertStoppedEarly(stopped, whole)
        }
    }

    private fun assertStoppedEarly(stopped: Long, whole: Long) {
        assertWithMessage("returned at $stopped ms, the whole page took $whole ms")
            .that(stopped)
            .isLessThan((whole * STOPPED_BEFORE).toLong())
    }

    private inline fun timed(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtime()
        block()
        return SystemClock.elapsedRealtime() - start
    }

    /** [count] lines of [lines], again and again, black on white, 40 px high. */
    private fun page(lines: List<String>, count: Int): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
        }
        val width = MARGIN * 2 + lines.maxOf { paint.measureText(it) }.toInt()
        val bitmap = createBitmap(width, MARGIN * 2 + count * LINE_HEIGHT)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        repeat(count) { line ->
            val baseline = (MARGIN + (line + 1) * LINE_HEIGHT).toFloat()
            canvas.drawText(lines[line % lines.size], MARGIN.toFloat(), baseline, paint)
        }
        return bitmap
    }

    private companion object {
        const val TAG = "OcrEngineTest"
        val LINES = listOf(
            "The quick brown fox jumps over the lazy dog",
            "Invoice number 4821 dated 22 October 2026",
            "Portez ce vieux whisky au juge blond qui fume",
            "Nothing in this picture leaves the phone",
        )

        /** A page of 120 lines: seconds of recognition, so that a stop shows. */
        const val DENSE_LINES = 120
        const val TEXT_SIZE = 40f
        const val LINE_HEIGHT = 64
        const val MARGIN = 40

        /** Cancelled at a fifth of the page; returned before three fifths: at a word, not at the end. */
        const val CANCEL_AT = 5
        const val STOPPED_BEFORE = 0.6
    }
}

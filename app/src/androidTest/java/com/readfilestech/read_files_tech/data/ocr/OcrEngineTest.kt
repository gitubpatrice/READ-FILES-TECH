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
 * Tesseract on the device (docs D-032), through the app's engine: it reads, a page in another language than
 * the app's is read again with its own first (D-033), and leaving STOPS it — a recognition cancelled, or its
 * engine closed, returns at Tesseract's next word, not at the end of the page. Timed against the same page
 * read whole, on the same device: the stop must come well before.
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
    fun aPageInAnotherLanguageIsReadAgainWithItsOwnFirst() {
        val french = OcrLanguages.order(OcrLanguages.FRENCH)
        val german = OcrLanguages.order(OcrLanguages.GERMAN)
        runBlocking {
            OcrEngine({ OcrModels.prepare(context) }, { OcrLanguages.FRENCH }, OcrEngine.PAGE_SEG_MODE).use { engine ->
                // A German page on a French phone: told German, read again — French first gives "Osterreich".
                val germanPage = page(GERMAN, GERMAN.size)
                assertThat(engine.recognize(germanPage)).contains("Österreich und die Schweiz")
                assertThat(engine.lastReadings).containsExactly(french, german).inOrder()
                // The next German page: once, German first.
                engine.recognize(germanPage)
                assertThat(engine.lastReadings).containsExactly(german)
                // A picture without text: not read again, no language would find any — nor the guess changed.
                assertThat(engine.recognize(page(FRENCH, 0)).trim()).isEmpty()
                assertThat(engine.lastReadings).containsExactly(german)
                // Text, but no language's words: read again with the app's language first.
                val figures = page(FIGURES, FIGURES.size)
                assertThat(engine.recognize(figures)).contains("4821")
                assertThat(engine.lastReadings).containsExactly(german, french).inOrder()
                // Its like after it (a series of receipts): once, with the app's language first.
                engine.recognize(figures)
                assertThat(engine.lastReadings).containsExactly(french)
                // A German page again: guessed French, told German, read again.
                assertThat(engine.recognize(germanPage)).contains("Österreich und die Schweiz")
                assertThat(engine.lastReadings).containsExactly(french, german).inOrder()
                // Then a French page: guessed German, told French, read again with French first.
                assertThat(engine.recognize(page(FRENCH, FRENCH.size))).contains("envoyée à Marseille en été")
                assertThat(engine.lastReadings).containsExactly(german, french).inOrder()
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
        val GERMAN = listOf(
            "Zwölf Boxkämpfer jagen Viktor quer über den Sylter Deich",
            "Österreich und die Schweiz liegen südlich von München",
            "Der Bäcker öffnet um sieben Uhr, die Tür ist grün",
            "Nichts auf diesem Bild verlässt das Telefon",
        )
        val FIGURES = listOf("4821 1234,56 EUR", "22.10.2026 15:30", "0042 7315 9960")
        val FRENCH = listOf(
            "Portez ce vieux whisky au juge blond qui fume",
            "Une lettre envoyée à Marseille en été",
            "Il est très tard et nous sommes dans la rue",
            "Rien de cette image ne quitte le téléphone",
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

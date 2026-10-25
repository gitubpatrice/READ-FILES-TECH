package com.readfilestech.read_files_tech.data.viewer.text

import android.util.Log
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The colours of a text are found in one pass over it, ON A DEVICE. Kotlin's `findAll` makes a new Matcher
 * for every match, and Android's Matcher copies its whole input into ICU when it is made: one copy per
 * match, 175 s for one HTML line of 20 MiB full of `#123456` on an emulator (HTML corpus, 2026-10-13). The
 * JVM's Matcher reads its input in place, so the JVM tests cannot see it: this one runs on the device.
 *
 * Calibrated on the device itself: the search may take [FACTOR] times what indexing the same text takes.
 */
class TextColorsSpeedTest {
    @Test
    fun coloursOfOneLongLineFullOfCodesAreFoundInLinearTime() = runBlocking {
        val chunk = "<b style=color:#123456>x</b>".toByteArray()
        val bytes = ByteArray(SIZE) { chunk[it % chunk.size] }

        var start = System.nanoTime()
        val document = TextDocument.of(bytes)
        val indexing = (System.nanoTime() - start) / NANOS_PER_MILLI

        start = System.nanoTime()
        val colors = TextColors.extract(document)
        val search = (System.nanoTime() - start) / NANOS_PER_MILLI
        Log.i("TextColorsSpeedTest", "indexing $indexing ms, colours $search ms")

        assertWithMessage("colours").that(colors.map(TextColor::code)).containsExactly("#123456")
        assertWithMessage("colours in $search ms, indexing in $indexing ms").that(search).isLessThan(FACTOR * indexing + FLOOR_MILLIS)
    }

    private companion object {
        /** 4 MiB: 150 000 codes on one line. */
        const val SIZE = 4 * 1024 * 1024
        const val FACTOR = 20
        const val FLOOR_MILLIS = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

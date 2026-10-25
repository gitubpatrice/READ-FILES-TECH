package com.readfilestech.read_files_tech.data.viewer.zip

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileMoves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.channels.ClosedChannelException

/**
 * `test/archive_extract_service_test.dart` of the 2.15.3, case by case: the caps, the zip-slip, the
 * total counted on what is written. Its last group crossed the isolate boundary (a closure that captured
 * the Flutter binding broke the extraction on the S9): there is no isolate here (inventory §10, "except
 * the isolate part"); what replaces it is how an extraction ends when it does not end by itself —
 * cancelled, or its archive gone — and what it leaves then.
 */
class ArchiveExtractTest : ZipTestBase() {
    private val out: File get() = File(tmp, "out")

    /** "Extract all" of [archive] into a new folder, as the viewer runs it. */
    private fun extract(archive: ZipArchive, entryCap: Long = 1L shl 20, totalCap: Long = 10L shl 20): ExtractionReport {
        out.mkdirs()
        val extraction = ZipExtraction(archive, out, entryCap, totalCap)
        runBlocking { extraction.run() }
        return extraction.report
    }

    private fun jdk(name: String, vararg entries: Pair<String, ByteArray>) = openArchive(jdkZip(File(tmp, name), entries.toList()))

    /** Everything under the folder, links included, by path; the temporaries of the writing too. */
    private fun tree(): List<String> = out.walkTopDown().drop(1).map {
        it.relativeTo(out).invariantSeparatorsPath
    }.sorted().toList()

    @Test
    fun `extracts an honest archive`() {
        val report = extract(jdk("sain.zip", "a.txt" to "aaaaa".toByteArray(), "sous/b.txt" to "bbb".toByteArray()))

        assertThat(report.written).isEqualTo(2)
        assertThat(report.refused).isEmpty()
        assertThat(report.bytes).isEqualTo(8)
        assertThat(File(out, "a.txt").readText()).isEqualTo("aaaaa")
        assertThat(File(out, "sous/b.txt").readText()).isEqualTo("bbb")
    }

    @Test
    fun `refuses an entry whose header lies, and goes on`() {
        // The heart of it: 64 KiB real, 10 bytes declared, 1 KiB cap. The old guard read the DECLARED size.
        val report = extract(forged(Forged("bombe.bin", ByteArray(64 * 1024), declaredSize = 10)), entryCap = 1024)

        assertThat(report.written).isEqualTo(0)
        assertThat(report.refused.map { it.second }).containsExactly(NotExtracted.Unreadable(ZipProblem.DAMAGED))
        assertThat(report.bytes).isEqualTo(0)
        // Nothing under its name, and no half file under another.
        assertThat(tree()).isEmpty()
    }

    @Test
    fun `a refused entry does not stop the others`() {
        val archive = jdk(
            "mixte.zip",
            "petit.txt" to "abc".toByteArray(),
            "gros.bin" to ByteArray(8192),
            "autre.txt" to "xyz".toByteArray()
        )
        val report = extract(archive, entryCap = 1024)

        assertThat(report.written).isEqualTo(2)
        assertThat(
            report.refused.map { it.first.name to it.second }
        ).containsExactly("gros.bin" to NotExtracted.Unreadable(ZipProblem.TOO_LARGE))
        assertThat(tree()).containsExactly("autre.txt", "petit.txt")
    }

    @Test
    fun `zip-slip - a name that leaves the folder is set aside`() {
        val report = extract(jdk("slip.zip", "../../evade.txt" to "boom".toByteArray(), "normal.txt" to "ok".toByteArray()))

        assertThat(report.written).isEqualTo(1)
        assertThat(report.refused.map { it.second }).containsExactly(NotExtracted.UnsafeName)
        assertThat(File(out, "normal.txt").exists()).isTrue()
        // The witness: nothing landed above the destination.
        assertThat(File(tmp, "evade.txt").exists()).isFalse()
        assertThat(File(tmp.parentFile, "evade.txt").exists()).isFalse()
    }

    @Nested
    inner class Total {
        @Test
        fun `the total is counted on what is written, not on what is declared`() {
            // Three entries of 4 KiB, a total of 6 KiB: two cannot both fit; the third finds less left.
            val archive = jdk("cumul.zip", "e0.bin" to ByteArray(4096), "e1.bin" to ByteArray(4096), "e2.bin" to ByteArray(4096))
            val report = extract(archive, entryCap = 4096, totalCap = 6 * 1024)

            assertThat(report.bytes).isAtMost(6L * 1024)
            assertThat(report.written + report.refused.size).isEqualTo(3)
            assertThat(report.written).isLessThan(3)
        }

        @Test
        fun `entries declaring 1 byte for 4 KiB each write nothing, whatever the total leaves`() {
            // What the Dart test's comment described: counted on the DECLARED size, the three would pass.
            val lying = (0 until 3).map { Forged("e$it.bin", ByteArray(4096), declaredSize = 1) }
            val report = extract(forged(*lying.toTypedArray()), entryCap = 4096, totalCap = 6 * 1024)

            assertThat(report.written).isEqualTo(0)
            assertThat(report.bytes).isEqualTo(0)
            assertThat(report.refused.map { it.second }.toSet()).containsExactly(NotExtracted.Unreadable(ZipProblem.DAMAGED))
        }

        @Test
        fun `the total stops the extraction once used up, and says so`() {
            // The Dart service threw; here it stops, and the report keeps what was written.
            val entries = (0 until 4).map { "e$it.bin" to ByteArray(2048) }.toTypedArray()
            val report = extract(jdk("plein.zip", *entries), entryCap = 2048, totalCap = 4096)

            assertThat(report.written).isEqualTo(2)
            assertThat(report.stop).isEqualTo(ExtractionStop.TotalReached)
            assertThat(tree()).containsExactly("e0.bin", "e1.bin")
        }
    }

    /** `safeJoin` of the 2.15.3. */
    @Nested
    inner class SafeJoin {
        @Test
        fun `accepts an ordinary name`() {
            assertThat(ZipPaths.segments("a/b.txt")).containsExactly("a", "b.txt").inOrder()
        }

        @Test
        fun `refuses the dangerous paths`() {
            listOf("", "/absolu.txt", "../evade.txt", "a/../../evade.txt", "./rien.txt", "C:/windows/system32").forEach { bad ->
                assertThat(ZipPaths.segments(bad)).isNull()
            }
        }

        @Test
        fun `refuses a name holding a NUL byte`() {
            assertThat(ZipPaths.segments("a\u0000b.txt")).isNull()
        }

        @Test
        fun `turns a backslash into a separator first, then judges - the traversal through it is refused`() {
            // Windows PowerShell 5.1 writes `docs\notes.md`; `..\..\x` is the same traversal.
            val item = forged(Forged("docs\\notes.md", "n".toByteArray()), Forged("..\\..\\x.txt", "x".toByteArray()), Forged("C:\\x.txt"))
            assertThat(item.entries.map { it.path }).containsExactly(listOf("docs", "notes.md"), null, null).inOrder()
        }

        @Test
        fun `drops empty segments, refuses a control character`() {
            assertThat(ZipPaths.segments("a//b/")).containsExactly("a", "b").inOrder()
            assertThat(ZipPaths.segments("a/b\nc.txt")).isNull()
            assertThat(ZipPaths.segments("/")).isNull()
        }
    }

    /** What replaces the isolate group: an extraction that does not end by itself says what it wrote. */
    @Nested
    inner class Interrupted {
        @Test
        fun `a cancellation keeps the files written and removes the one in hand`() {
            // 100 MiB of zeros take a while to write: cancelled as soon as its hidden temporary shows.
            val archive = forged(
                Forged("un.bin", ByteArray(1000)),
                Forged("deux.bin", zeros = 100L shl 20),
                Forged("trois.bin", ByteArray(10))
            )
            out.mkdirs()
            val extraction = ZipExtraction(archive, out)
            var sawPart = false
            runBlocking {
                val job = launch(Dispatchers.IO) { extraction.run() }
                while (job.isActive && !sawPart) {
                    sawPart = out.list().orEmpty().any { it.startsWith(FileMoves.PART_PREFIX) }
                    if (!sawPart) delay(1)
                }
                job.cancelAndJoin()
            }
            assertThat(sawPart).isTrue()
            assertThat(extraction.report.written).isEqualTo(1)
            assertThat(tree()).containsExactly("un.bin")
        }

        @Test
        fun `an archive closed under it stops it, the reason kept, nothing half written`() {
            val archive = forged(Forged("un.bin", ByteArray(1000)), Forged("deux.bin", ByteArray(1000)))
            out.mkdirs()
            val extraction = ZipExtraction(archive, out)
            runBlocking { extraction.run { done, _ -> if (done == 1) archive.close() } }

            val stop = extraction.report.stop
            assertThat(stop).isInstanceOf(ExtractionStop.Failed::class.java)
            assertThat((stop as ExtractionStop.Failed).error).isInstanceOf(ClosedChannelException::class.java)
            assertThat(tree()).containsExactly("un.bin")
        }

        @Test
        fun `a folder where nothing was written is removed, the folders it made with it`() {
            val archive = forged(Forged("dossier/"), Forged("dossier/secret.bin", encrypted = true))
            out.mkdirs()
            val extraction = ZipExtraction(archive, out)
            runBlocking { extraction.run() }

            assertThat(extraction.report.written).isEqualTo(0)
            assertThat(extraction.discardIfEmpty()).isTrue()
            assertThat(out.exists()).isFalse()
        }

        @Test
        fun `a folder holding a file is never removed`() {
            val extraction = ZipExtraction(forged(Forged("a.txt", "a".toByteArray())), out.apply { mkdirs() })
            runBlocking { extraction.run() }

            assertThat(extraction.discardIfEmpty()).isFalse()
            assertThat(File(out, "a.txt").readText()).isEqualTo("a")
        }
    }
}

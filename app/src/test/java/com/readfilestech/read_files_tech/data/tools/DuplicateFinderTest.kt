package com.readfilestech.read_files_tech.data.tools

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * The duplicate finder. The first six tests are `test/duplicate_finder_service_test.dart` of the 2.15.3,
 * ported (its isolate became a cancellable coroutine); the others are its defects D8 and D9, and what the
 * port adds.
 */
class DuplicateFinderTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var root: File

    @BeforeEach
    fun setUp() {
        root = File(tmp, "root").apply { mkdirs() }
    }

    private fun write(name: String, content: String) = File(root, name).apply {
        parentFile.mkdirs()
        writeText(content)
    }

    /** Above the 4 KiB threshold: below it, nothing is compared (the Dart suite's `gros`). */
    private fun big(seed: String): String = buildString { while (length < 8192) append(seed) }

    private fun find(minBytes: Long = 4096, maxFiles: Int = 200_000): DuplicateReport = runBlocking {
        DuplicateFinder.find(root, minBytes = minBytes, maxFiles = maxFiles)
    }

    private fun DuplicateGroup.names() = files.map { File(it.path).name }.toSet()

    @Test
    fun `finds two identical files`() {
        write("a.txt", big("same content "))
        write("copie/b.txt", big("same content "))
        write("autre.txt", big("different "))
        assertThat(find().groups.single().names()).containsExactly("a.txt", "b.txt")
    }

    @Test
    fun `does not group different files`() {
        write("x.txt", big("aaa "))
        write("y.txt", big("bbb "))
        assertThat(find().groups).isEmpty()
    }

    @Test
    fun `an empty folder ends, it does not hang`() {
        val report = runBlocking { withTimeout(20_000) { DuplicateFinder.find(root) } }
        assertThat(report.groups).isEmpty()
        assertThat(report.filesListed).isEqualTo(0)
    }

    @Test
    fun `a folder that does not exist is an error, not a silence`() {
        assertThrows<FileNotFoundException> { runBlocking { DuplicateFinder.find(File(root, "missing")) } }
    }

    @Test
    fun `two scans in a row give the same result`() {
        write("a.txt", big("z "))
        write("b.txt", big("z "))
        val first = find()
        val second = runBlocking { withTimeout(20_000) { DuplicateFinder.find(root) } }
        assertThat(first.groups).hasSize(1)
        assertThat(second).isEqualTo(first)
    }

    /** The Dart: a second call overwrote the first's isolate, and the first never completed. */
    @Test
    fun `two concurrent scans both end`() {
        (0 until 40).forEach {
            write("lot/f_$it.txt", big("content $it "))
            write("lot/g_$it.txt", big("content $it "))
        }
        runBlocking {
            val reports = withTimeout(
                25_000
            ) { listOf(async { DuplicateFinder.find(root) }, async { DuplicateFinder.find(root) }).awaitAll() }
            assertThat(reports[0].groups).hasSize(40)
            assertThat(reports[1]).isEqualTo(reports[0])
        }
    }

    /** The 2.15.3 killed its isolate; here the coroutine stops before the next file it would read. */
    @Test
    fun `a cancelled scan stops at once`() {
        (0 until 40).forEach {
            write("lot/f_$it.txt", big("content $it "))
            write("lot/g_$it.txt", big("content $it "))
        }
        runBlocking {
            val sampling = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val after = AtomicInteger()
            val job = launch(Dispatchers.IO) {
                DuplicateFinder.find(root, onProgress = { progress ->
                    if (sampling.isCompleted) after.incrementAndGet()
                    if (progress is DuplicateProgress.Sampling && sampling.complete(Unit)) release.await()
                })
            }
            sampling.await()
            job.cancel()
            release.countDown()
            job.join()
            assertThat(job.isCancelled).isTrue()
            assertThat(after.get()).isEqualTo(0)
        }
    }

    /** D8: the trash's copies were listed as duplicates of their originals. */
    @Test
    fun `the trash, hidden entries, Android data and cache folders are left out`() {
        write("photo.jpg", big("pixels "))
        write(".RFT_Corbeille/items/1_2/photo.jpg", big("pixels "))
        write(".RFT_Corbeille.next/items/1_2/photo.jpg", big("pixels "))
        write(".hidden/photo.jpg", big("pixels "))
        write("Android/data/com.app/photo.jpg", big("pixels "))
        write("cache/photo.jpg", big("pixels "))
        val report = find()
        assertThat(report.groups).isEmpty()
        assertThat(report.filesListed).isEqualTo(1)
    }

    @Test
    fun `files of the same size, same beginning and end, but a different middle are not duplicates`() {
        val head = "h".repeat(70_000)
        val tail = "t".repeat(70_000)
        write("one.bin", head + "AAAA" + tail)
        write("two.bin", head + "BBBB" + tail)
        write("three.bin", head + "AAAA" + tail)
        assertThat(find().groups.single().names()).containsExactly("one.bin", "three.bin")
    }

    @Test
    fun `files under the threshold, empty ones included, are not compared`() {
        write("e1.txt", "")
        write("e2.txt", "")
        write("s1.txt", "small")
        write("s2.txt", "small")
        assertThat(find().filesListed).isEqualTo(0)
        assertThat(find(minBytes = 1).groups.single().names()).containsExactly("s1.txt", "s2.txt")
    }

    @Test
    fun `groups come most wasteful first, with the largest files and what each wastes`() {
        // The small ones first on the disk: the order of the report is not the walk's.
        write("a/small1.txt", big("s "))
        write("a/small2.txt", big("s "))
        write("z/large1.txt", big("l ").repeat(4))
        write("z/large2.txt", big("l ").repeat(4))
        write("z/large3.txt", big("l ").repeat(4))
        val report = find()
        assertThat(report.groups.map { it.files.size }).containsExactly(3, 2).inOrder()
        assertThat(report.groups.first().wastedBytes).isEqualTo(2L * File(root, "z/large1.txt").length())
        assertThat(report.largest.first().path).endsWith("large1.txt")
        assertThat(report.largest).hasSize(5)
    }

    @Test
    fun `the listing stops at its cap, and says so`() {
        (1..6).forEach { write("f$it.txt", big("same ")) }
        val report = find(maxFiles = 4)
        assertThat(report.truncated).isTrue()
        assertThat(report.filesListed).isEqualTo(4)
        assertThat(report.groups.single().files).hasSize(4)
    }

    /** One file under two names frees nothing when one name goes; worse, it is no copy at all. */
    @Test
    fun `two links to one file are one file`() {
        val original = write("a.txt", big("linked "))
        val linked = runCatching { Files.createLink(File(root, "b.txt").toPath(), original.toPath()) }.isSuccess
        assumeTrue(linked, "this volume makes no hard link")
        val attributes = Files.readAttributes(original.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        assumeTrue(attributes.fileKey() != null, "this file system gives no file key")
        val report = find()
        assertThat(report.groups).isEmpty()
        assertThat(report.filesListed).isEqualTo(1)
    }

    @Test
    fun `the full digest is the file's SHA-256`() {
        val digest = FileHashes.sha256("abc".byteInputStream())
        assertThat(digest).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }
}

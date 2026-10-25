package com.readfilestech.read_files_tech.ui.viewer

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.files.BoundedInputStream
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import com.readfilestech.read_files_tech.startup.AppProcess
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import com.readfilestech.read_files_tech.ui.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.AccessDeniedException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The frame every viewer of phase 4 is plugged into: the table of extensions, the caps, the failures, the processes. */
class ViewerTest {
    private fun entry(name: String) = FileEntry("/d/$name", name, false, 0, 0, isSymlink = false)

    /** One table: an extension opens in one viewer at most, and only one the home's "Open a file" offers. */
    @Test
    fun `each extension belongs to one viewer, and the home offers it`() {
        val all = ViewerKind.entries.flatMap { it.extensions }
        assertThat(all).containsNoDuplicates()
        assertThat(FileRouter.VIEWABLE).containsAtLeastElementsIn(all)
        all.forEach { assertThat(it).isEqualTo(it.lowercase()) }
    }

    @Test
    fun `a file with a viewer goes to it, the others to another app`() {
        assertThat(FileRouter.targetFor(entry("notes.TXT"))).isEqualTo(FileTarget.Internal("viewer/TEXT?path=%2Fd%2Fnotes.TXT"))
        assertThat(FileRouter.chooserTargetFor(entry("notes.txt"))).isEqualTo(FileTarget.External)
        assertThat(FileRouter.targetFor(entry("song.mp3"))).isEqualTo(FileTarget.External)
        assertThat(FileRouter.targetFor(FileEntry("/d/x.txt", "x.txt", true, 0, 0, isSymlink = false))).isEqualTo(FileTarget.External)
        assertThat(Routes.viewer(ViewerKind.TEXT, "/a b/é.txt")).isEqualTo("viewer/TEXT?path=%2Fa%20b%2F%C3%A9.txt")
    }

    @Test
    fun `a route names a viewer by its exact name, or none`() {
        ViewerKind.entries.forEach { assertThat(ViewerKind.fromArgument(it.name)).isEqualTo(it) }
        listOf(null, "", "text", "TEXT ", "../TEXT").forEach { assertThat(ViewerKind.fromArgument(it)).isNull() }
    }

    @Test
    fun `a stream past its cap is refused, not cut`() {
        val exact = BoundedInputStream(ByteArrayInputStream(ByteArray(4)), 4)
        assertThat(exact.readBytes()).hasLength(4)
        val over = BoundedInputStream(ByteArrayInputStream(ByteArray(5)), 4)
        assertThat(assertThrows<FileTooLargeException> { over.readBytes() }.capBytes).isEqualTo(4)
        val skipped = BoundedInputStream(ByteArrayInputStream(ByteArray(5)), 4)
        assertThrows<FileTooLargeException> { skipped.skip(5) }
        val byByte = BoundedInputStream(ByteArrayInputStream(ByteArray(2)), 1)
        byByte.read()
        assertThrows<FileTooLargeException> { byByte.read() }
    }

    @Test
    fun `a failure is said in words, the cap named`() {
        assertThat(ViewerErrors.failureOf(FileTooLargeException(7))).isEqualTo(ViewerFailure.TooLarge(7))
        assertThat(ViewerErrors.failureOf(StackOverflowError())).isEqualTo(ViewerErrors.failureOf(UnreadableFileException("x")))
        listOf(
            UnreadableFileException("x"),
            UnsupportedFormatException("x"),
            AccessDeniedException("x"),
            IllegalStateException(),
            OutOfMemoryError()
        )
            .map(ViewerErrors::failureOf)
            .forEach { assertThat(it).isInstanceOf(ViewerFailure.Reason::class.java) }
        assertThat(
            ViewerErrors.failureOf(UnreadableFileException("x"))
        ).isNotEqualTo(ViewerErrors.failureOf(UnsupportedFormatException("x")))
    }

    /** A parser that runs out of memory or of stack on a hostile file is a refusal, not a crash of the app. */
    @Test
    fun `a load that fails, even with an Error, ends in words`(): Unit = runBlocking {
        val errors = listOf(OutOfMemoryError(), StackOverflowError(), IllegalStateException(), UnreadableFileException("x"))
        errors.forEach { error ->
            val content = ViewerContent<String>(this, Dispatchers.Unconfined) { throw error }
            val state = content.state.first { it !is ViewerState.Loading }
            assertThat(state).isInstanceOf(ViewerState.Failed::class.java)
            content.close()
        }
        val ready = ViewerContent(this, Dispatchers.Unconfined) { "text" }
        assertThat(ready.state.first { it !is ViewerState.Loading }).isEqualTo(ViewerState.Ready("text"))
        ready.close()
    }

    /**
     * Claude review R9: the screen left while a parser was blocked in its call, and what the load had kept
     * was closed under it (a PdfRenderer with a page open refuses, and stays open). Closed once it returns.
     */
    @Test
    fun `what a running load kept is closed when the load stops, not before`() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        // A scope of its own: this thread only waits, as the main thread does while the load runs.
        val content = ViewerContent(CoroutineScope(Dispatchers.Default), Dispatchers.IO) {
            keep(AutoCloseable { closed.countDown() })
            opened.countDown()
            release.await() // a blocking parser: deaf to the cancellation
            "late"
        }
        assertThat(opened.await(5, TimeUnit.SECONDS)).isTrue()
        content.close()
        assertThat(closed.await(200, TimeUnit.MILLISECONDS)).isFalse()
        release.countDown()
        assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue()
    }

    /**
     * The CSV and text viewers' "Edit": back from the editor, the file saved is read again — the 2.15.3's CSV viewer
     * showed the table of before, as if the save had done nothing — and what the first read kept is closed.
     */
    @Test
    fun `a watched file is read again once it changed, not before, and the first read's resources are closed`(): Unit = runBlocking {
        val file = File.createTempFile("viewer", ".csv").apply { writeText("one") }
        try {
            var reads = 0
            var closed = 0
            val content = ViewerContent(this, Dispatchers.Unconfined, file) {
                keep(AutoCloseable { closed++ })
                reads++
                file.readText()
            }
            assertThat(content.state.first { it !is ViewerState.Loading }).isEqualTo(ViewerState.Ready("one"))
            content.reloadIfChanged()!!.join()
            assertThat(reads).isEqualTo(1)

            file.writeText("two, longer")
            content.reloadIfChanged()!!.join()
            assertThat(content.state.first { it !is ViewerState.Loading }).isEqualTo(ViewerState.Ready("two, longer"))
            assertThat(reads).isEqualTo(2)
            assertThat(closed).isEqualTo(1)
            content.close()
            assertThat(closed).isEqualTo(2)

            // Nothing watched: never read again.
            val unwatched = ViewerContent(this, Dispatchers.Unconfined) { reads++ }
            unwatched.state.first { it !is ViewerState.Loading }
            file.writeText("three, longer still")
            assertThat(unwatched.reloadIfChanged()).isNull()
            assertThat(reads).isEqualTo(3)
            unwatched.close()
        } finally {
            file.delete()
        }
    }

    /** What a load opened is closed with the viewer — even what it opens after the viewer left. */
    @Test
    fun `resources kept by a load are closed once, late ones at once`() {
        val resources = ViewerResources()
        var closed = 0
        resources.keep(AutoCloseable { closed++ })
        resources.close()
        assertThat(closed).isEqualTo(1)
        resources.close()
        assertThat(closed).isEqualTo(1)
        resources.keep(AutoCloseable { closed++ })
        assertThat(closed).isEqualTo(2)
    }

    /** Docs D-029: only the main process cleans up at start; a worker, or a name unknown, does not. */
    @Test
    fun `only the package's own process is the main one`() {
        assertThat(AppProcess.isMain("com.readfilestech.read_files_tech.next", "com.readfilestech.read_files_tech.next")).isTrue()
        listOf(
            "com.readfilestech.read_files_tech.next:pdf",
            "com.readfilestech.read_files_tech",
            "",
            null,
        ).forEach { assertThat(AppProcess.isMain(it, "com.readfilestech.read_files_tech.next")).isFalse() }
    }
}

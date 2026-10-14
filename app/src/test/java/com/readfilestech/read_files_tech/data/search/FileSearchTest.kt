package com.readfilestech.read_files_tech.data.search

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The global search: what it keeps, what it skips, where it stops. */
class FileSearchTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var root: File

    @BeforeEach
    fun setUp() {
        root = File(tmp, "root").apply { mkdirs() }
    }

    private fun file(path: String, content: String = "x") = File(root, path).apply {
        parentFile.mkdirs()
        writeText(content)
    }

    private fun hits(query: SearchQuery): List<SearchHit> = runBlocking {
        FileSearch.run(query).filterIsInstance<SearchEvent.Found>().toList().flatMap { it.hits }
    }

    private fun names(query: SearchQuery) = hits(query).map { File(it.path).name }.sorted()

    @Test
    fun `by name, case ignored, through sub-folders`() {
        file("Factures/Facture-2024.pdf")
        file("deep/a/b/facture.txt")
        file("other.txt")
        assertThat(names(SearchQuery(root, name = "FACTURE"))).containsExactly("Facture-2024.pdf", "facture.txt")
    }

    @Test
    fun `by content, text files only, with the matching line as snippet`() {
        file("notes.txt", "first\nSIRET 123 456\nlast")
        file("image.png", "SIRET in a binary")
        val hit = hits(SearchQuery(root, content = "siret")).single()
        assertThat(File(hit.path).name).isEqualTo("notes.txt")
        assertThat(hit.snippet).isEqualTo("SIRET 123 456")
    }

    /** A content pattern alone used to keep EVERY file in the 2.15.x before its fix: absent is neutral, not a match. */
    @Test
    fun `a name or a content, either matching keeps the file`() {
        file("rapport.pdf")
        file("memo.txt", "rapport annuel")
        file("other.txt", "nothing")
        assertThat(names(SearchQuery(root, name = "rapport", content = "rapport"))).containsExactly("memo.txt", "rapport.pdf")
        assertThat(names(SearchQuery(root, content = "rapport"))).containsExactly("memo.txt")
    }

    @Test
    fun `hidden files and everything under a hidden folder are skipped`() {
        file("DCIM/.thumbnails/photo.jpg")
        file(".secret.txt")
        file("DCIM/photo.jpg")
        assertThat(names(SearchQuery(root, name = "photo"))).containsExactly("photo.jpg")
        assertThat(names(SearchQuery(root, name = "secret"))).isEmpty()
    }

    @Test
    fun `a root chosen inside a hidden folder is searched`() {
        val hidden = File(root, ".local").apply { mkdirs() }
        File(hidden, "notes.txt").writeText("x")
        assertThat(hits(SearchQuery(hidden, name = "notes"))).hasSize(1)
    }

    /** The 2.15.x decoded strict UTF-8: a Latin-1 text never matched anything. */
    @Test
    fun `a Latin-1 text file is still searched by content`() {
        File(root, "latin.txt").writeBytes(byteArrayOf(0x63, 0xE9.toByte(), 0x0A) + "contrat signé".toByteArray(Charsets.ISO_8859_1))
        assertThat(names(SearchQuery(root, content = "contrat"))).containsExactly("latin.txt")
    }

    /** D35, whole: replacing the bad bytes found "contrat", never "signé" — the accented words were lost. */
    @Test
    fun `an accented word of a Latin-1 text is found`() {
        File(root, "latin.txt").writeBytes("contrat signé".toByteArray(Charsets.ISO_8859_1))
        assertThat(names(SearchQuery(root, content = "SIGNÉ"))).containsExactly("latin.txt")
    }

    @Test
    fun `content beyond the size cap is not read, the name can still match`() {
        file("big.txt", "needle " + "x".repeat(100))
        assertThat(hits(SearchQuery(root, content = "needle", maxContentBytes = 10))).isEmpty()
        assertThat(hits(SearchQuery(root, name = "big", content = "needle", maxContentBytes = 10)).single().snippet).isNull()
    }

    @Test
    fun `stops at the result cap`() {
        (1..30).forEach { file("f$it.txt") }
        assertThat(hits(SearchQuery(root, name = "f", maxResults = 7))).hasSize(7)
    }

    @Test
    fun `a long matching line is cut at 200 characters`() {
        file("long.txt", "key " + "y".repeat(400))
        val snippet = hits(SearchQuery(root, content = "key")).single().snippet!!
        assertThat(snippet).hasLength(201)
        assertThat(snippet).endsWith("…")
    }

    @Test
    fun `the last event says how many files were scanned, and a collector can stop early`() = runBlocking {
        (1..5).forEach { file("f$it.txt") }
        val last = FileSearch.run(SearchQuery(root, name = "f")).toList().last()
        assertThat(last).isEqualTo(SearchEvent.Scanned(5))
        // Taking one event cancels the walk: no exception, no further work.
        assertThat(FileSearch.run(SearchQuery(root, name = "f")).take(1).toList()).hasSize(1)
    }
}

package com.readfilestech.read_files_tech.data.history

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** docs D-021: the old history read on the exact name, the new one kept per path. */
class EditorHistoryTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var legacy: File
    private lateinit var history: EditorHistory

    @BeforeEach
    fun setUp() {
        legacy = File(tmp, "app_flutter/history").apply { mkdirs() }
        history = EditorHistory(legacy, File(tmp, "files/editor-history"))
    }

    private fun bak(name: String, content: String = name) = File(legacy, name).apply { writeText(content) }

    @Test
    fun `the old copies of a name are found on that exact name only - D6`() {
        bak("notes.txt_100.bak")
        bak("notes.txt_300.bak")
        bak("mes_notes.txt_200.bak") // `contains("notes.txt_")` took it for a copy of notes.txt
        bak("notes.txt_x.bak")
        assertThat(history.legacyFor("notes.txt").map { it.file.name })
            .containsExactly("notes.txt_300.bak", "notes.txt_100.bak").inOrder()
        assertThat(history.legacyFor("mes_notes.txt").map { it.savedAtMs }).containsExactly(200L)
    }

    @Test
    fun `the old name is derived unit by unit as Dart did - accents and emoji`() {
        assertThat(EditorHistory.legacyBase("Relevé été 2026.txt")).isEqualTo("Relev___t__2026.txt")
        // An emoji is two UTF-16 units: two underscores in Dart, one in a Java regex.
        assertThat(EditorHistory.legacyBase("a" + String(Character.toChars(0x1F600)) + ".md")).isEqualTo("a__.md")
        // The three answers of Dart 3.13.2 itself (2026-10-11).
        assertThat(EditorHistory.legacyBase("x-y (2).json")).isEqualTo("x_y__2_.json")
        bak("Relev___t__2026.txt_5.bak")
        assertThat(history.legacyFor("Relevé été 2026.txt")).hasSize(1)
    }

    @Test
    fun `a name with regex characters is matched literally`() {
        bak("a.b_1.bak")
        bak("aXb_2.bak")
        assertThat(history.legacyFor("a.b").map { it.file.name }).containsExactly("a.b_1.bak")
    }

    @Test
    fun `the new history is kept per path, ten each, and never touches the old copies`() {
        bak("notes.txt_1.bak")
        repeat(12) { history.saveBefore("/storage/emulated/0/a/notes.txt", "v$it".toByteArray(), nowMs = 1_000L + it) }
        history.saveBefore("/storage/emulated/0/b/notes.txt", "other".toByteArray(), nowMs = 5_000L)
        val versions = history.versions("/storage/emulated/0/a/notes.txt")
        assertThat(versions).hasSize(EditorHistory.KEPT)
        assertThat(versions.first().file.readText()).isEqualTo("v11")
        assertThat(history.versions("/storage/emulated/0/b/notes.txt").single().file.readText()).isEqualTo("other")
        assertThat(history.legacyFor("notes.txt")).hasSize(1)
    }

    @Test
    fun `two saves in the same millisecond keep both copies`() {
        history.saveBefore("/storage/emulated/0/doc.txt", "v1".toByteArray(), nowMs = 1_000)
        history.saveBefore("/storage/emulated/0/doc.txt", "v2".toByteArray(), nowMs = 1_000)
        assertThat(history.versions("/storage/emulated/0/doc.txt").map { it.file.readText() }).containsExactly("v1", "v2")
    }

    @Test
    fun `the old history counts and goes on request only`() {
        bak("a_1.bak", "12345")
        bak("b_2.bak", "123")
        File(legacy, "readme").writeText("not a copy")
        val all = history.legacyAll()
        assertThat(all).hasSize(2)
        assertThat(all.sumOf { it.size }).isEqualTo(8)
        assertThat(history.deleteLegacy()).isTrue()
        assertThat(legacy.exists()).isFalse()
    }

    /** Claude review R20: the clock set back, the new copy was named below the ten newest and deleted at once. */
    @Test
    fun `a copy saved after the clock went back is the newest, and kept`() {
        repeat(EditorHistory.KEPT) { history.saveBefore("/a.csv", "v$it".toByteArray(), nowMs = 2_000L + it) }
        history.saveBefore("/a.csv", "after".toByteArray(), nowMs = 100)
        val versions = history.versions("/a.csv")
        assertThat(versions).hasSize(EditorHistory.KEPT)
        assertThat(versions.first().file.readText()).isEqualTo("after")
    }

    /** Claude review R21: after the clock went ahead once, every later copy was shown dated ahead. */
    @Test
    fun `a copy is shown at the time it was written, not the time its name took`() {
        val ahead = 4_102_444_800_000 // 2100-01-01
        val now = 1_700_000_000_000
        history.saveBefore("/a.csv", "then".toByteArray(), nowMs = ahead)
        history.saveBefore("/a.csv", "now".toByteArray(), nowMs = now)
        val versions = history.versions("/a.csv")
        assertThat(versions.map { it.file.readText() }).containsExactly("now", "then").inOrder()
        assertThat(versions.map { it.writtenAtMs }).containsExactly(now, ahead).inOrder()
    }

    /** Claude review R22: restores browsing the history each kept a version already there, and the oldest went. */
    @Test
    fun `a version already kept is not kept twice, and the oldest stays`() {
        repeat(EditorHistory.KEPT) { history.saveBefore("/a.csv", "v$it".toByteArray(), nowMs = 1_000L + it) }
        history.saveBefore("/a.csv", "v5".toByteArray(), nowMs = 5_000)
        val versions = history.versions("/a.csv")
        assertThat(versions.map { it.file.readText() }).containsExactlyElementsIn((9 downTo 0).map { "v$it" }).inOrder()
        assertThat(versions.single { it.file.readText() == "v5" }.savedAtMs).isEqualTo(1_005L)
        // The same size, other bytes: a new version, the oldest out.
        history.saveBefore("/a.csv", "vX".toByteArray(), nowMs = 6_000)
        val after = history.versions("/a.csv").map { it.file.readText() }
        assertThat(after).containsExactlyElementsIn(listOf("vX") + (9 downTo 1).map { "v$it" })
    }

    /** Claude review R20: a copy that failed left an empty name, listed as a version and pushing a real one out. */
    @Test
    fun `a copy that fails to be written leaves no version behind`() {
        val failing = EditorHistory(legacy, File(tmp, "files/editor-history")) { _, _ -> throw IOException("no space") }
        history.saveBefore("/a.csv", "kept".toByteArray(), nowMs = 1)
        assertThrows<IOException> { failing.saveBefore("/a.csv", "lost".toByteArray(), nowMs = 2) }
        assertThat(history.versions("/a.csv").map { it.file.readText() }).containsExactly("kept")
    }
}

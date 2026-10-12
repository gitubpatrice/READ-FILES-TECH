package com.readfilestech.read_files_tech.data.recents

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.DartDateTime
import com.readfilestech.read_files_tech.data.prefs.FlutterPreferences
import com.readfilestech.read_files_tech.data.prefs.FlutterPrefsFormat
import com.readfilestech.read_files_tech.testing.InMemorySharedPreferences
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDateTime

class RecentFilesTest {
    @TempDir
    lateinit var tmp: File

    private val raw = InMemorySharedPreferences()
    private val recents = RecentFiles(FlutterPreferences(raw)) { true }

    private lateinit var a: File
    private lateinit var b: File

    @BeforeEach
    fun files() {
        a = File(tmp, "a.pdf").apply { writeText("aaaaa") }
        b = File(tmp, "b été.txt").apply { writeText("bb") }
    }

    /** One entry as the core's `jsonEncode(toJson())` wrote it. */
    private fun dart(path: File, lastOpened: String, size: Int, favorite: Boolean = false) =
        """{"path":"${path.path.replace("\\", "\\\\")}","name":"${path.name}","lastOpened":"$lastOpened",""" +
            """"sizeBytes":$size,"isFavorite":$favorite}"""

    private fun plant(vararg entries: String) = raw.plant("flutter.recent_files", FlutterPrefsFormat.encodeStringList(entries.toList()))

    private fun stored(): List<String> =
        (FlutterPreferences(raw).stringList("recent_files") as com.readfilestech.read_files_tech.data.prefs.PrefRead.Present).value

    @Test
    fun `a list the Flutter app wrote loads, the most recent first, favourites kept`() {
        plant(dart(a, "2026-10-10T09:00:00.000", 5), dart(b, "2026-10-11T13:05:07.123456", 2, favorite = true))
        val loaded = recents.load()
        assertThat(loaded.map { it.name }).containsExactly("b été.txt", "a.pdf").inOrder()
        assertThat(loaded.first().isFavorite).isTrue()
        assertThat(loaded.first().lastOpened.toIso8601String()).isEqualTo("2026-10-11T13:05:07.123456")
    }

    @Test
    fun `an entry whose file is gone is cleaned up - a positive finding`() {
        val gone = File(tmp, "gone.txt")
        plant(dart(a, "2026-10-10T09:00:00.000", 5), dart(gone, "2026-10-11T00:00:00.000", 1))
        assertThat(recents.load().map { it.name }).containsExactly("a.pdf")
        assertThat(stored()).hasSize(1)
    }

    @Test
    fun `an entry only UNSEEN - permission revoked, card taken out - is hidden, never dropped (D-019)`() {
        val unseen = File(tmp, "carte/retiree.pdf") // its folder is not there: a card taken out
        plant(dart(a, "2026-10-10T09:00:00.000", 5), dart(unseen, "2026-10-11T00:00:00.000", 1, favorite = true))
        assertThat(recents.load().map { it.name }).containsExactly("a.pdf")
        assertThat(stored()).hasSize(2) // kept, favourite mark included
        val blind = RecentFiles(FlutterPreferences(raw)) { false } // everything visible no more
        plant(dart(a, "2026-10-10T09:00:00.000", 5), dart(File(tmp, "gone.txt"), "2026-10-11T00:00:00.000", 1))
        blind.load()
        assertThat(stored()).hasSize(2)
    }

    @Test
    fun `two writers at once lose nothing`() {
        val files = (1..16).map { File(tmp, "f$it.txt").apply { writeText("$it") } }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        files.map { f -> pool.submit { recents.addOrUpdate(f.path) } }.forEach { it.get() }
        pool.shutdown()
        assertThat(recents.load().map { it.path }.toSet()).isEqualTo(files.map { it.path }.toSet())
    }

    @Test
    fun `an entry that cannot be read is kept as it is, never dropped in a rewrite - D-019`() {
        val broken = """{"path":"/x","name":"x","lastOpened":"hier","sizeBytes":1}"""
        val gone = File(tmp, "gone.txt")
        plant(dart(a, "2026-10-10T09:00:00.000", 5), broken, dart(gone, "2026-10-11T00:00:00.000", 1))
        assertThat(recents.load().map { it.name }).containsExactly("a.pdf") // the gone one triggers a rewrite
        assertThat(stored()).containsExactly(dart(a, "2026-10-10T09:00:00.000", 5), broken).inOrder()
        recents.addOrUpdate(b.path)
        assertThat(stored().last()).isEqualTo(broken)
    }

    @Test
    fun `a list that cannot be read at all is neither shown nor rewritten`() {
        raw.plant("flutter.recent_files", 42L)
        assertThat(recents.load()).isEmpty()
        recents.addOrUpdate(a.path)
        assertThat(raw.all["flutter.recent_files"]).isEqualTo(42L)
    }

    @Test
    fun `opening a file puts it first, keeps its favourite mark, and writes it as the core did`() {
        plant(dart(a, "2026-10-10T09:00:00.000", 5, favorite = true), dart(b, "2026-10-09T09:00:00.000", 2))
        val updated = recents.addOrUpdate(a.path)
        assertThat(updated.map { it.name }).containsExactly("a.pdf", "b été.txt").inOrder()
        assertThat(updated.first().isFavorite).isTrue()
        assertThat(stored().first()).startsWith("""{"path":""")
        assertThat(stored().first()).endsWith(""""sizeBytes":5,"isFavorite":true}""")
    }

    @Test
    fun `the list is cut at twenty`() {
        val files = (1..25).map { File(tmp, "f$it.txt").apply { writeText("x") } }
        files.forEach { recents.addOrUpdate(it.path) }
        assertThat(recents.load()).hasSize(RecentFiles.MAX_FILES)
        assertThat(recents.load().map { it.name }).doesNotContain("f1.txt")
    }

    /** Inventory D44: the 2.15.x evicted a favourite like any entry once 20 newer files were opened. */
    @Test
    fun `a favourite is never cut by the cap`() {
        recents.addOrUpdate(a.path)
        recents.toggleFavorite(a.path)
        val files = (1..25).map { File(tmp, "f$it.txt").apply { writeText("x") } }
        files.forEach { recents.addOrUpdate(it.path) }
        val kept = recents.load()
        assertThat(kept).hasSize(RecentFiles.MAX_FILES)
        assertThat(kept.filter { it.isFavorite }.map { it.name }).containsExactly("a.pdf")
        assertThat(kept.first().name).isEqualTo("f25.txt")
    }

    /** Inventory F27: removed from the picker, a file reopened was hidden there for good. */
    @Test
    fun `removing a file hides it until it is opened again`() {
        recents.addOrUpdate(a.path)
        recents.dismiss(a.path)
        assertThat(recents.load()).isEmpty()
        assertThat(recents.dismissed()).containsExactly(a.path)
        recents.addOrUpdate(a.path)
        assertThat(recents.dismissed()).isEmpty()
        assertThat(recents.load().map { it.path }).containsExactly(a.path)
    }

    @Test
    fun `a folder or a missing file is never recorded`() {
        recents.addOrUpdate(tmp.path)
        recents.addOrUpdate(File(tmp, "nope.txt").path)
        assertThat(recents.load()).isEmpty()
    }

    @Test
    fun `remove and toggleFavorite rewrite the list`() {
        recents.addOrUpdate(a.path)
        recents.addOrUpdate(b.path)
        assertThat(recents.toggleFavorite(a.path).first { it.path == a.path }.isFavorite).isTrue()
        assertThat(recents.remove(b.path).map { it.name }).containsExactly("a.pdf")
    }

    @Test
    fun `an entry is read exactly where the core read it`() {
        val ok = RecentFile.fromJson("""{"path":"/p","name":"n","lastOpened":"2026-10-11T00:00:00.000","sizeBytes":0}""")
        assertThat(ok?.isFavorite).isFalse()
        listOf(
            """{"path":"/p","name":"n","lastOpened":"2026-10-11T00:00:00.000","sizeBytes":1.0}""",
            """{"path":"/p","name":"n","lastOpened":"2026-10-11T00:00:00.000","sizeBytes":-1}""",
            """{"path":"/p","name":"n","lastOpened":"2026-10-11T00:00:00.000","sizeBytes":1,"isFavorite":"yes"}""",
            """{"path":"/p","name":"","lastOpened":"2026-10-11T00:00:00.000","sizeBytes":1}""",
            """{"path":"","name":"n","lastOpened":"2026-10-11T00:00:00.000","sizeBytes":1}""",
            """{"path":"/p","name":"n","lastOpened":"hier","sizeBytes":1}""",
            """["not","an","object"]""",
            """{"path":"/p",}""",
        ).forEach { assertThat(RecentFile.fromJson(it)).isNull() }
    }

    @Test
    fun `an entry the port writes reads back identical`() {
        val entry = RecentFile(
            path = "/storage/emulated/0/a \"q\".pdf",
            name = "a \"q\".pdf",
            lastOpened = DartDateTime.local(LocalDateTime.of(2026, 10, 11, 1, 2, 3)),
            sizeBytes = 7,
            isFavorite = true,
        )
        assertThat(RecentFile.fromJson(entry.toJson())).isEqualTo(entry)
        assertThat(entry.toJson()).isEqualTo(
            """{"path":"/storage/emulated/0/a \"q\".pdf","name":"a \"q\".pdf","lastOpened":"2026-10-11T01:02:03.000",""" +
                """"sizeBytes":7,"isFavorite":true}""",
        )
    }

    @Test
    fun `dismissed paths are added once, and an unreadable list is left alone`() {
        recents.dismiss("/a")
        recents.dismiss("/a")
        recents.dismiss("/b")
        assertThat(recents.dismissed()).containsExactly("/a", "/b")
        raw.plant("flutter.recents_dismissed_paths", true)
        recents.dismiss("/c")
        assertThat(raw.all["flutter.recents_dismissed_paths"]).isEqualTo(true)
    }
}

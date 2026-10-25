package com.readfilestech.read_files_tech.data.trash

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.DartDateTime
import com.readfilestech.read_files_tech.data.DartJson
import com.readfilestech.read_files_tech.data.files.SharedStorageNames
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The 21 tests of the 2.15.3 (`test/trash_service_test.dart`), ported one for one, plus the format
 * shared with it. As there, paths of a test are on no Android volume: the trash falls back to
 * [fallback].
 */
class TrashStoreTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var work: File
    private lateinit var fallback: File
    private lateinit var trash: TrashStore
    private val names = SharedStorageNames(replacesFlutterApp = true)

    @BeforeEach
    fun setUp() {
        work = File(tmp, "work").apply { mkdirs() }
        fallback = File(tmp, "app_flutter").apply { mkdirs() }
        trash = store()
    }

    private fun makeFile(name: String, content: String) = File(work, name).apply { writeText(content) }

    /**
     * The app's private folder is `tmp/private` here, what a forged restore must never reach — and the
     * trash in [fallback] stands for a trash on SHARED storage, whose metadata another app can forge.
     * [privateRoots] including [fallback] makes it the trusted private trash of the device.
     */
    private fun store(
        names: SharedStorageNames = this.names,
        visible: Boolean = true,
        privateRoots: List<File> = listOf(File(tmp, "private")),
    ) = TrashStore(
        names,
        fallback,
        privateRoots,
        visibility = { visible },
        volumeBases = { emptyList() },
        primary = File("/storage/emulated/0"),
    )

    @Test
    fun `moves a file to the trash and away from its place`() {
        val f = makeFile("note.txt", "hello")
        val entry = trash.moveToTrash(f)
        assertThat(f.exists()).isFalse()
        assertThat(entry.payload.readText()).isEqualTo("hello")
        assertThat(entry.meta.exists()).isTrue()
        assertThat(entry.originalPath).isEqualTo(f.path)
        assertThat(entry.size).isEqualTo(5)
        assertThat(entry.isDir).isFalse()
    }

    @Test
    fun `list reads back the metadata written`() {
        trash.moveToTrash(makeFile("a.txt", "a"))
        trash.moveToTrash(makeFile("b.txt", "bb"))
        assertThat(trash.list().map { it.name }.toSet()).containsExactly("a.txt", "b.txt")
    }

    @Test
    fun `restore puts the file back where it was`() {
        val f = makeFile("doc.txt", "contenu")
        val entry = trash.moveToTrash(f)
        val dest = trash.restore(entry)
        assertThat(dest).isEqualTo(f)
        assertThat(dest.readText()).isEqualTo("contenu")
        assertThat(entry.payload.exists()).isFalse()
        assertThat(entry.meta.exists()).isFalse()
        assertThat(trash.list()).isEmpty()
    }

    @Test
    fun `restore never replaces an existing file`() {
        val f = makeFile("doc.txt", "ancien")
        val entry = trash.moveToTrash(f)
        makeFile("doc.txt", "nouveau")
        val dest = trash.restore(entry)
        assertThat(dest).isEqualTo(File(work, "doc (1).txt"))
        assertThat(dest.readText()).isEqualTo("ancien")
        assertThat(f.readText()).isEqualTo("nouveau")
    }

    @Test
    fun `restore recreates a parent folder that disappeared`() {
        val sub = File(work, "sub").apply { mkdirs() }
        val entry = trash.moveToTrash(File(sub, "x.txt").apply { writeText("x") })
        sub.deleteRecursively()
        assertThat(trash.restore(entry).readText()).isEqualTo("x")
    }

    @Test
    fun `a folder goes to the trash with all it holds, and comes back`() {
        val dir = File(work, "photos").apply { mkdirs() }
        File(dir, "1.txt").writeText("un")
        File(dir, "nested").mkdirs()
        File(dir, "nested/2.txt").writeText("deux")
        val entry = trash.moveToTrash(dir)
        assertThat(dir.exists()).isFalse()
        assertThat(entry.isDir).isTrue()
        assertThat(File(entry.payload, "nested/2.txt").readText()).isEqualTo("deux")
        trash.restore(entry)
        assertThat(File(dir, "nested/2.txt").readText()).isEqualTo("deux")
    }

    @Test
    fun `a restored folder never merges into one that appeared meanwhile`() {
        val dir = File(work, "photos").apply { mkdirs() }
        File(dir, "1.txt").writeText("from the trash")
        val entry = trash.moveToTrash(dir)
        File(work, "photos").mkdirs()
        File(work, "photos/mine.txt").writeText("mine")
        val dest = trash.restore(entry)
        assertThat(dest).isEqualTo(File(work, "photos (1)"))
        assertThat(File(dest, "1.txt").readText()).isEqualTo("from the trash")
        assertThat(File(work, "photos").list()!!.toList()).containsExactly("mine.txt")
    }

    @Test
    fun `deleteForever erases payload and metadata`() {
        val entry = trash.moveToTrash(makeFile("z.txt", "z"))
        trash.deleteForever(entry)
        assertThat(entry.payload.exists()).isFalse()
        assertThat(entry.meta.exists()).isFalse()
        assertThat(entry.itemDir.exists()).isFalse()
        assertThat(trash.list()).isEmpty()
    }

    @Test
    fun `emptyAll empties every entry`() {
        trash.moveToTrash(makeFile("a.txt", "a"))
        trash.moveToTrash(makeFile("b.txt", "b"))
        assertThat(trash.emptyAll(trash.list())).isEqualTo(2 to 0)
        assertThat(trash.list()).isEmpty()
    }

    @Test
    fun `list cleans an orphan metadata file - its payload is gone`() {
        val entry = trash.moveToTrash(makeFile("orph.txt", "o"))
        entry.payload.delete()
        assertThat(trash.list()).isEmpty()
        assertThat(entry.meta.exists()).isFalse()
    }

    @Test
    fun `the trash itself cannot go to the trash - nor the real one from a next build`() {
        val self = File(fallback, names.trashDir).apply { mkdirs() }
        assertThrows<TrashException> { trash.moveToTrash(self) }
        val next = store(SharedStorageNames(replacesFlutterApp = false))
        assertThat(next.isTrashRelated(File(tmp, ".RFT_Corbeille").path)).isTrue()
        assertThat(next.isTrashRelated(File(tmp, ".RFT_Corbeille/items/1/x.txt").path)).isTrue()
        assertThat(next.isTrashRelated("/storage/emulated/0")).isTrue()
    }

    @Test
    fun `a forged metadata file with a traversal id is ignored`() {
        val victim = makeFile("victime.txt", "ne pas toucher")
        val entry = trash.moveToTrash(makeFile("vrai.txt", "v"))
        entry.meta.writeText(
            DartJson.encode(
                linkedMapOf(
                    "id" to "../..",
                    "originalPath" to File(work, "ailleurs.txt").path,
                    "name" to "victime.txt",
                    "isDir" to false,
                    "size" to 1L,
                    "deletedAt" to DartDateTime.now().toIso8601String(),
                ),
            ),
        )
        assertThat(trash.list()).isEmpty()
        assertThat(victim.readText()).isEqualTo("ne pas toucher")
    }

    @Test
    fun `an id that is not the 2_15_x pattern is ignored, even when everything else fits`() {
        // Its file is named after it and its payload exists: only the id pattern can refuse it.
        val root = File(fallback, names.trashDir)
        File(root, "items/abc").mkdirs()
        File(root, "items/abc/x.txt").writeText("x")
        File(root, "meta").mkdirs()
        File(root, "meta/abc.json").writeText(
            """{"id":"abc","originalPath":"/storage/emulated/0/x.txt","name":"x.txt","isDir":false,"size":1,""" +
                """"deletedAt":"2026-10-11T13:05:07.000"}""",
        )
        assertThat(trash.list()).isEmpty()
    }

    @Test
    fun `a metadata file whose name is not its id is ignored`() {
        val entry = trash.moveToTrash(makeFile("x.txt", "x"))
        File(File(entry.trashRoot, TrashEntry.META_DIR), "intrus.json").writeText(entry.meta.readText())
        assertThat(trash.list()).hasSize(1)
    }

    @Test
    fun `restore refuses a relative original path or one with dot-dot`() {
        val entry = trash.moveToTrash(makeFile("r.txt", "r"))
        val forged = entry.copy(originalPath = work.path + "/../evade.txt")
        assertThrows<TrashException> { trash.restore(forged) }
        assertThrows<TrashException> { trash.restore(entry.copy(originalPath = "relative/r.txt")) }
    }

    @Test
    fun `restore never writes into the app's private folders, whatever a forged entry says`() {
        val entry = trash.moveToTrash(makeFile("sel.txt", "forged"))
        // A shared trash's entry forged to aim at the app's private folder (the vault's salt).
        val forged = entry.copy(originalPath = File(tmp, "private/vault/sel.txt").path)
        assertThrows<TrashException> { trash.restore(forged) }
        assertThat(File(tmp, "private/vault").exists()).isFalse()
        assertThat(entry.payload.readText()).isEqualTo("forged") // still in the trash
    }

    @Test
    fun `the private fallback trash, which only the app writes, puts a private file back where it was`() {
        // An output the 2.15.x kept in app_flutter/Files Tech/ when shared storage refused it.
        val private = store(privateRoots = listOf(fallback))
        val output = File(fallback, "Files Tech/Scans/scan.pdf").apply {
            parentFile.mkdirs()
            writeText("scan")
        }
        val entry = private.moveToTrash(output)
        assertThat(private.restore(entry).readText()).isEqualTo("scan")
        assertThat(output.exists()).isTrue()
    }

    @Test
    fun `restore refuses a name that is not the last segment of the original path, or a target in a trash`() {
        val entry = trash.moveToTrash(makeFile("doc.txt", "d"))
        assertThrows<TrashException> { trash.restore(entry.copy(name = "autre.txt")) }
        // A trash folder that is NOT under the private root: only the trash rule can refuse it.
        val intoTrash = entry.copy(originalPath = File(work, ".RFT_Corbeille/meta/doc.txt").path)
        assertThrows<TrashException> { trash.restore(intoTrash) }
        assertThat(entry.payload.exists()).isTrue()
    }

    @Test
    fun `a payload that is only UNSEEN keeps its entry - gone, positively, cleans it (D-019)`() {
        val entry = trash.moveToTrash(makeFile("photo.jpg", "p"))
        entry.payload.delete()
        val blind = store(visible = false)
        assertThat(blind.list()).isEmpty() // not shown...
        assertThat(entry.meta.exists()).isTrue() // ...nor touched: another app's file may be hidden
        assertThat(trash.list()).isEmpty()
        assertThat(entry.meta.exists()).isFalse() // seen gone: cleaned
    }

    @Test
    fun `a restore that fails leaves no empty reservation where the item was`() {
        val f = makeFile("rapport.pdf", "r")
        val entry = trash.moveToTrash(f)
        entry.payload.delete() // the move will fail
        assertThrows<IOException> { trash.restore(entry) }
        assertThat(f.exists()).isFalse()
        assertThat(entry.meta.exists()).isTrue()
    }

    @Test
    fun `a file that does not exist throws instead of creating an empty entry`() {
        assertThrows<FileNotFoundException> { trash.moveToTrash(File(work, "fantome.txt")) }
        assertThat(trash.list()).isEmpty()
    }

    @Test
    fun `ten simultaneous deletions get ten DISTINCT ids`() {
        val files = (0 until 10).map { makeFile("doc$it.txt", "contenu $it") }
        val pool = Executors.newFixedThreadPool(10)
        val entries = pool.invokeAll(files.map { f -> Callable { trash.moveToTrash(f) } }).map { it.get() }
        pool.shutdown()
        assertThat(entries.map { it.id }.toSet()).hasSize(10)
        assertThat(trash.list()).hasSize(10)
    }

    @Test
    fun `deleting one entry for good does not take another with it`() {
        val pool = Executors.newFixedThreadPool(2)
        val entries = pool.invokeAll(
            listOf(makeFile("a.txt", "AAA"), makeFile("b.txt", "BBB")).map { f -> Callable { trash.moveToTrash(f) } },
        ).map { it.get() }
        pool.shutdown()
        trash.deleteForever(entries.first())
        val left = trash.list()
        assertThat(left).hasSize(1)
        assertThat(left.single().payload.readText()).isAnyOf("AAA", "BBB")
    }

    @Test
    fun `restoring does not overwrite a file that appeared meanwhile`() {
        val entry = trash.moveToTrash(makeFile("note.txt", "ORIGINAL"))
        File(work, "note.txt").writeText("NOUVEAU")
        val dest = trash.restore(entry)
        assertThat(File(work, "note.txt").readText()).isEqualTo("NOUVEAU")
        assertThat(dest).isNotEqualTo(File(work, "note.txt"))
        assertThat(dest.readText()).isEqualTo("ORIGINAL")
    }

    @Test
    fun `two simultaneous restores do not overlap`() {
        val e1 = trash.moveToTrash(makeFile("meme.txt", "PREMIER"))
        val e2 = trash.moveToTrash(makeFile("meme.txt", "SECOND"))
        val pool = Executors.newFixedThreadPool(2)
        val dests = pool.invokeAll(listOf(e1, e2).map { e -> Callable { trash.restore(e) } }).map { it.get() }
        pool.shutdown()
        assertThat(dests.toSet()).hasSize(2)
        assertThat(dests.map { it.readText() }.toSet()).containsExactly("PREMIER", "SECOND")
    }

    @Test
    fun `a VALID but huge metadata file is ignored - only the cap can refuse it`() {
        trash.moveToTrash(makeFile("vrai.txt", "ok"))
        padMeta(2 * 1024 * 1024)
        assertThat(trash.list().map { it.name }).containsExactly("vrai.txt")
    }

    @Test
    fun `the same metadata, under the cap, is read`() {
        val name = padMeta(1024)
        assertThat(trash.list().map { it.name }).contains(name)
    }

    @Test
    fun `a normal metadata file is read`() {
        trash.moveToTrash(makeFile("petit.txt", "x"))
        assertThat(trash.list().map { it.name }).containsExactly("petit.txt")
    }

    // ---------------------------------------------------------------------------------------------
    // The format shared with the 2.15.x, both ways.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an entry the Flutter app wrote is listed and restored`() {
        // What `jsonEncode(entry.toJson())` of the 2.15.3 writes: keys in that order, a local time to the
        // microsecond, a size that is an int.
        val root = File(fallback, ".RFT_Corbeille")
        val id = "1760180000000_4294967295"
        File(root, "items/$id").mkdirs()
        File(root, "items/$id/Relevé été.pdf").writeText("pdf")
        File(root, "meta").mkdirs()
        val original = File(work, "Relevé été.pdf").path
        val json = """{"id":"$id","originalPath":${DartJson.encode(original)},"name":"Relevé été.pdf",""" +
            """"isDir":false,"size":3,"deletedAt":"2026-10-11T13:05:07.123456"}"""
        File(root, "meta/$id.json").writeText(json)
        val entry = trash.list().single()
        assertThat(entry.deletedAt.toIso8601String()).isEqualTo("2026-10-11T13:05:07.123456")
        assertThat(entry.size).isEqualTo(3)
        assertThat(trash.restore(entry).readText()).isEqualTo("pdf")
    }

    @Test
    fun `the port writes its metadata exactly as the Flutter app does`() {
        val entry = TrashEntry(
            id = "1760180000000_7",
            trashRoot = fallback,
            originalPath = "/storage/emulated/0/Download/a \"b\".txt",
            name = "a \"b\".txt",
            isDir = false,
            size = 12,
            deletedAt = DartDateTime.local(LocalDateTime.of(2026, 10, 11, 13, 5, 7, 123_000_000)),
        )
        assertThat(entry.toJson()).isEqualTo(
            """{"id":"1760180000000_7","originalPath":"/storage/emulated/0/Download/a \"b\".txt",""" +
                """"name":"a \"b\".txt","isDir":false,"size":12,"deletedAt":"2026-10-11T13:05:07.123"}""",
        )
    }

    @Test
    fun `values of the wrong type made Dart skip the entry - so does the port`() {
        val root = File(fallback, ".RFT_Corbeille")
        // `1e400` is infinity, on which Dart's toInt() throws (Claude review R3).
        listOf(""""size":"3"""", """"deletedAt":12""", """"id":12""", """"size":1e400""").forEachIndexed { i, broken ->
            val id = "17601800000${i}0_1"
            File(root, "items/$id").mkdirs()
            File(root, "items/$id/x").writeText("x")
            File(root, "meta").mkdirs()
            val base = mapOf(
                "id" to "\"$id\"",
                "originalPath" to "\"/storage/emulated/0/x\"",
                "name" to "\"x\"",
                "isDir" to "false",
                "size" to "1",
                "deletedAt" to "\"2026-10-11T13:05:07.000\"",
            )
            val key = broken.substringBefore(':').trim('"')
            val fields = base + (key to broken.substringAfter(':'))
            File(root, "meta/$id.json").writeText(fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" })
        }
        assertThat(trash.list()).isEmpty()
    }

    @Test
    fun `a next build keeps its trash apart from the real one - docs D-017`() {
        assertThat(SharedStorageNames(replacesFlutterApp = false).trashDir).isEqualTo(".RFT_Corbeille.next")
        assertThat(SharedStorageNames(replacesFlutterApp = false).filesTechDir).isEqualTo("Files Tech.next")
        assertThat(SharedStorageNames(replacesFlutterApp = true).trashDir).isEqualTo(".RFT_Corbeille")
        val next = store(SharedStorageNames(replacesFlutterApp = false))
        val entry = next.moveToTrash(makeFile("t.txt", "t"))
        assertThat(entry.trashRoot.name).isEqualTo(".RFT_Corbeille.next")
        assertThat(trash.list()).isEmpty() // the real trash does not see it
    }

    @Test
    fun `volume roots are found as the 2_15_x found them`() {
        assertThat(TrashStore.volumeRootOf("/storage/emulated/0/Download/a.txt")).isEqualTo("/storage/emulated/0")
        assertThat(TrashStore.volumeRootOf("/sdcard/a.txt")).isEqualTo("/sdcard")
        assertThat(TrashStore.volumeRootOf("/storage/1A2B-3C4D/DCIM/x.jpg")).isEqualTo("/storage/1A2B-3C4D")
        assertThat(TrashStore.volumeRootOf("/storage/self/primary/a")).isNull()
        assertThat(TrashStore.volumeRootOf("/data/user/0/x")).isNull()
    }

    /**
     * In a work profile, the trash is listed under its `/sdcard` name (the first base that holds it) and
     * its items carry `/storage/emulated/10/…`: one volume, `/sdcard` being the USER's primary — read as
     * `/storage/emulated/0`, every restore there was refused (Claude review R6).
     */
    @Test
    fun `sdcard is the primary volume of the current user`() {
        val profile = "/storage/emulated/10"
        fun entry(trashRoot: String, originalPath: String) = TrashEntry(
            id = "1760180000000_7",
            trashRoot = File(trashRoot),
            originalPath = originalPath,
            name = File(originalPath).name,
            isDir = false,
            size = 1,
            deletedAt = DartDateTime.now(),
        )
        val private = listOf(File(tmp, "private"))

        assertThat(TrashStore.mayRestoreTo(entry("/sdcard/.RFT_Corbeille", "$profile/Download/a.txt"), private, profile)).isTrue()
        assertThat(TrashStore.mayRestoreTo(entry("$profile/.RFT_Corbeille", "/sdcard/Download/a.txt"), private, profile)).isTrue()
        // Still one volume per trash: the owner's storage is another volume, from a profile's trash.
        assertThat(TrashStore.mayRestoreTo(entry("/sdcard/.RFT_Corbeille", "/storage/emulated/0/Download/a.txt"), private, profile))
            .isFalse()
        assertThat(TrashStore.mayRestoreTo(entry("$profile/.RFT_Corbeille", "/storage/emulated/0/Download/a.txt"), private, profile))
            .isFalse()
    }

    /** A VALID metadata file of [padding] bytes, its payload present: only the cap can refuse it. */
    private fun padMeta(padding: Int): String {
        val model = trash.moveToTrash(makeFile("gros.txt", "x"))

        @Suppress("UNCHECKED_CAST")
        val json = (DartJson.decode(model.meta.readText()) as Map<String, Any?>).toMutableMap()
        json["bourrage"] = "A".repeat(padding)
        model.meta.writeText(DartJson.encode(json))
        return model.name
    }
}

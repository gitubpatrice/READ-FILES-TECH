package com.readfilestech.read_files_tech.data.scanner

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A scan in progress survives the death of the process: its JSON, its files, and what they may name. */
class ScanSessionTest {
    @TempDir
    lateinit var tmp: File

    private val frame = listOf(Point(0.125, 0.0625), Point(0.875, 0.1), Point(0.9, 0.95), Point(0.05, 0.9))
    private val settings = PageSettings(frame, turns = 1, filter = PageFilter.WHITE_PAPER)

    private fun session(pages: Int = 2): ScanSession {
        var s = ScanSession(ScanFiles.newId(), filter = PageFilter.GREY)
        repeat(pages) { i ->
            val source = if (i % 2 == 0) {
                PageSource.Owned("src-${ScanFiles.newId()}.jpg")
            } else {
                PageSource.External("/storage/emulated/0/DCIM/été $i.jpg")
            }
            s = s.withPage(ScanPage(ScanFiles.newId(), source, settings, revision = i))
        }
        return s.copy(draft = ScanDraft(page = s.pages.first().id, source = s.pages.first().source, settings = settings))
    }

    @Test
    fun `a session written and read back is the same session`() {
        val files = ScanFiles(File(tmp, "scanner"))
        val s = session()
        assertThat(ScanSession.fromJson(s.toJson())).isEqualTo(s)
        val noDraft = s.copy(draft = null)
        assertThat(ScanSession.fromJson(noDraft.toJson())).isEqualTo(noDraft)
    }

    /** The process dies: a new ScanFiles over the same folder finds the session and its files. */
    @Test
    fun `after the process died, the session and its pages are found again`() {
        val root = File(tmp, "scanner")
        val before = ScanFiles(root)
        val capture = before.captures.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val id = ScanFiles.newId()
        val owned = before.captures.adopt(capture, id)
        val page = ScanPage(ScanFiles.newId(), owned, settings, revision = 3)
        val s = ScanSession(id).withPage(page)
        before.pageFile(id, page).writeBytes(byteArrayOf(9))
        before.save(s)

        val after = ScanFiles(root)
        val restored = after.load(id)
        assertThat(restored).isEqualTo(s)
        val restoredPage = requireNotNull(restored).pages.single()
        assertThat(after.sourceFile(id, restoredPage.source).readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(after.pageFile(id, restoredPage).readBytes()).isEqualTo(byteArrayOf(9))
        // The photo left the folder the FileProvider serves.
        assertThat(capture.exists()).isFalse()
        assertThat(after.captures.dir.list()).isEmpty()
    }

    @Test
    fun `what the JSON names is checked before it can name a file`() {
        val files = ScanFiles(File(tmp, "scanner"))
        val good = session().toJson()
        val pageId = Regex("\"pages\":\\[\\{\"id\":\"([0-9a-f]{16})\"").find(good)!!.groupValues[1]
        val ownName = Regex("\"own\":\"(src-[0-9a-f]{16}\\.jpg)\"").find(good)!!.groupValues[1]
        val hostile = listOf(
            good.replace(ownName, "../../files/vault/x.enc"),
            good.replace(ownName, "src-0123456789abcdef.jpg/../x"),
            good.replace("\"/storage/emulated/0/DCIM/", "\"relative/"),
            good.replace("\"/storage/emulated/0/DCIM/", "\"/storage/\\u0000/"),
            good.replace(pageId, "../$pageId"),
            good.replace("\"v\":1", "\"v\":2"),
            good.replace("\"turns\":1", "\"turns\":4"),
            good.replace("\"filter\":\"GREY\"", "\"filter\":\"SEPIA\""),
            good.replace("\"quad\":[125000,", "\"quad\":[1250000,"),
            good.replace("\"quad\":[125000,", "\"quad\":[\"125000\","),
            good.replace("\"own\":\"$ownName\"", "\"own\":\"$ownName\",\"path\":\"/x\""),
            good.replace("\"revision\":0", "\"revision\":-1"),
            good.dropLast(1),
            "[]",
            "",
        )
        hostile.forEachIndexed { i, json -> assertWithMessage("case $i").that(ScanSession.fromJson(json)).isNull() }
    }

    @Test
    fun `two pages with one id, a crossed frame, or 26 pages are not a session`() {
        val files = ScanFiles(File(tmp, "scanner"))
        val s = session()
        val twin = s.toJson().replace(s.pages[1].id, s.pages[0].id)
        assertThat(ScanSession.fromJson(twin)).isNull()
        val crossed = s.toJson().replaceFirst(
            "\"quad\":[125000,62500,875000,100000,900000,950000,50000,900000]",
            "\"quad\":[0,0,1000000,1000000,1000000,0,0,1000000]"
        )
        assertThat(crossed).isNotEqualTo(s.toJson())
        assertThat(ScanSession.fromJson(crossed)).isNull()
        val full = session(pages = ScanSession.MAX_PAGES)
        assertThat(full.isFull).isTrue()
        val firstPage = requireNotNull(Regex("\\{\"id\":\"[0-9a-f]{16}\"[^}]*\\},").find(full.toJson())).value
        val tooMany = full.toJson().replace("\"pages\":[", "\"pages\":[" + firstPage.replace(full.pages[0].id, ScanFiles.newId()))
        assertThat(ScanSession.fromJson(tooMany)).isNull()
        assertThrows<IllegalArgumentException> {
            full.withPage(ScanPage(ScanFiles.newId(), PageSource.External("/a.jpg"), settings, 0))
        }
    }

    @Test
    fun `pages are added, made again in place, moved and removed`() {
        val files = ScanFiles(File(tmp, "scanner"))
        val s = session(pages = 3)
        val (a, b, c) = s.pages
        val again = b.copy(revision = b.revision + 1)
        assertThat(s.withPage(again).pages).containsExactly(a, again, c).inOrder()
        assertThat(s.moved(0, 2).pages).containsExactly(b, c, a).inOrder()
        assertThat(s.moved(2, 0).pages).containsExactly(c, a, b).inOrder()
        assertThat(s.moved(1, 3)).isEqualTo(s)
        assertThat(s.moved(-1, 0)).isEqualTo(s)
        assertThat(s.without(b.id).pages).containsExactly(a, c).inOrder()
    }

    @Test
    fun `a session file of another id, too large, or absent is not loaded`() {
        val root = File(tmp, "scanner")
        val files = ScanFiles(root)
        val s = session()
        files.save(s)
        val other = ScanFiles.newId()
        File(root, other).mkdirs()
        File(files.sessionDir(s.id), "session.json").copyTo(File(files.sessionDir(other), "session.json"))
        assertThat(files.load(other)).isNull()
        assertThat(files.load(ScanFiles.newId())).isNull()
        assertThat(files.load("../${s.id}")).isNull()
        File(files.sessionDir(s.id), "session.json").writeText(" ".repeat(2 shl 20))
        assertThat(files.load(s.id)).isNull()
    }

    @Test
    fun `a page's files go with it, an imported photo never does`() {
        val root = File(tmp, "scanner")
        val files = ScanFiles(root)
        val id = ScanFiles.newId()
        val imported = File(tmp, "DCIM/photo.jpg").apply {
            parentFile.mkdirs()
            writeText("photo")
        }
        val external = ScanPage(ScanFiles.newId(), PageSource.External(imported.path), settings, revision = 2)
        val owned = ScanPage(ScanFiles.newId(), files.captures.adopt(files.captures.newFile(), id), settings, revision = 0)
        listOf(external, owned).forEach { p ->
            (0..p.revision).forEach { r -> files.pageFile(id, p.copy(revision = r)).writeText("page") }
        }
        files.deleteOldRevisions(id, external)
        val revisions = files.sessionDir(id).list().orEmpty().filter { it.startsWith("page-${external.id}") }
        assertThat(revisions).containsExactly("page-${external.id}-2.jpg")
        files.deletePageFiles(id, external)
        files.deletePageFiles(id, owned)
        assertThat(imported.readText()).isEqualTo("photo")
        assertThat(files.sessionDir(id).list()).isEmpty()
    }

    @Test
    fun `only the scanner's own captures are discarded as captures`() {
        val root = File(tmp, "scanner")
        val files = ScanFiles(root)
        val capture = files.captures.newFile()
        assertThat(files.captures.isOwn(capture)).isTrue()
        val elsewhere = File(tmp, "${ScanFiles.newId()}.jpg").apply { writeText("x") }
        val misnamed = File(files.captures.dir, "photo.jpg").apply { writeText("x") }
        files.captures.discard(elsewhere)
        files.captures.discard(misnamed)
        assertThat(elsewhere.exists()).isTrue()
        assertThat(misnamed.exists()).isTrue()
        assertThrows<IllegalArgumentException> { files.captures.adopt(elsewhere, ScanFiles.newId()) }
        files.captures.discard(capture)
        assertThat(capture.exists()).isFalse()
    }

    @Test
    fun `the purge leaves nothing, and a missing folder is already purged`() {
        val root = File(tmp, "scanner")
        val files = ScanFiles(root)
        files.save(session())
        files.captures.newFile()
        assertThat(files.purge()).isTrue()
        assertThat(root.exists()).isFalse()
        assertThat(files.purge()).isTrue()
    }
}

package com.readfilestech.read_files_tech.data.viewer.zip

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.viewer.TooManyItemsException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.viewer.ViewerErrors
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailure
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** What the Dart suites never met: archives made by 7-Zip, names, links, duplicates, ZIP64, forged structures. */
class ZipArchiveTest : ZipTestBase() {
    private fun corpus(name: String) = openArchive(File(corpusZip, name))

    private fun files(archive: ZipArchive) = archive.entries.filterNot(ZipItem::isDirectory)

    /** Archives made by 7-Zip 26 on Windows (tools/corpus/zip, their making in corpus.json). */
    @Nested
    inner class SevenZip {
        @Test
        fun `java_util_zip refuses the plain 7-Zip archive whole - the reason this reader exists`() {
            // 7-Zip writes "Straße.csv" in code page 850, unflagged: ZipFile decodes it as UTF-8 and gives up.
            val refused = assertThrows<ZipException> { ZipFile(File(corpusZip, "7zip-deflate.zip")).close() }
            assertThat(refused.message).contains("bad entry name")
            assertThrows<ZipException> { ZipFile(File(corpusZip, "7zip-aes256.zip")).close() }
        }

        @Test
        fun `every file of the deflated archive reads, checksum checked, the same as the stored one`() {
            val deflated = corpus("7zip-deflate.zip")
            val stored = corpus("7zip-store.zip")
            assertThat(files(deflated).map { it.name }).containsExactlyElementsIn(files(stored).map { it.name })
            assertThat(deflated.entries.filter { it.data.method == ZipFormat.DEFLATED }).isNotEmpty()
            assertThat(stored.entries.filter { it.data.method == ZipFormat.DEFLATED }).isEmpty()
            files(deflated).forEach { item -> assertThat(deflated.bytesOf(item)).isEqualTo(stored.bytesOf(stored.entry(item.name))) }
        }

        @Test
        fun `names are decoded per entry - code page 850 unflagged, UTF-8 flagged`() {
            val names = corpus("7zip-deflate.zip").entries.map { it.name }
            assertThat(names).containsAtLeast("Relevé été 2026.txt", "docs/sous-dossier/Straße.csv", "docs/日本語.txt", "vide/")
        }

        @Test
        fun `an AES archive is listed, each entry refused as encrypted`() {
            val archive = corpus("7zip-aes256.zip")
            assertThat(files(archive)).isNotEmpty()
            files(archive).forEach { assertThat(it.problem).isEqualTo(ZipProblem.ENCRYPTED) }
            val refused = assertThrows<ZipEntryException> { archive.read(files(archive).first()) }
            assertThat(refused.problem).isEqualTo(ZipProblem.ENCRYPTED)
        }

        @Test
        fun `an archive with one ZipCrypto entry gives up that one only`() {
            val mixed = corpus("7zip-mixed-zipcrypto.zip")
            val reference = corpus("7zip-store.zip")
            assertThat(mixed.entry("lisez-moi.txt").problem).isEqualTo(ZipProblem.ENCRYPTED)
            val others = files(mixed).filter { it.name != "lisez-moi.txt" }
            assertThat(others).isNotEmpty()
            others.forEach { assertThat(mixed.bytesOf(it)).isEqualTo(reference.bytesOf(reference.entry(it.name))) }
        }

        @Test
        fun `a bzip2 entry is listed and refused as a method not supported, the stored one beside it read`() {
            // 7-Zip asked for bzip2 kept the 55-byte file stored: bzip2 would have made it larger.
            val archive = corpus("7zip-bzip2.zip")
            assertThat(archive.entry("docs/notes.md").problem).isEqualTo(ZipProblem.UNSUPPORTED_METHOD)
            assertThat(archive.entry("lisez-moi.txt").problem).isNull()
            assertThat(archive.bytesOf(archive.entry("lisez-moi.txt")).decodeToString()).startsWith("Bonjour")
        }
    }

    @Nested
    inner class Names {
        @Test
        fun `the embedded code page 437 is the JDK's`() {
            val high = ByteArray(128) { (0x80 + it).toByte() }
            assertThat(ZipNames.decode(high, utf8Flag = false)).isEqualTo(String(high, Charset.forName("IBM437")))
        }

        @Test
        fun `an unflagged name in valid UTF-8 is read as UTF-8, a flagged invalid one keeps its other characters`() {
            assertThat(ZipNames.decode("été.txt".toByteArray(), utf8Flag = false)).isEqualTo("été.txt")
            assertThat(ZipNames.decode(byteArrayOf(0x61, 0xE1.toByte(), 0x65), utf8Flag = false)).isEqualTo("aße")
            assertThat(ZipNames.decode(byteArrayOf(0x61, 0xFF.toByte(), 0x62), utf8Flag = true)).isEqualTo("a�b")
        }

        @Test
        fun `a name is shown without its control characters, shared by its last segment`() {
            val archive = forged(Forged("a/b\u0007c.txt", "x".toByteArray()), Forged("../../evade.txt"), Forged("dir/.."))
            assertThat(archive.entries[0].displayName).isEqualTo("b�c.txt")
            assertThat(archive.entries[0].folder).isEqualTo("a")
            assertThat(archive.entries[1].shareName).isEqualTo("evade.txt")
            assertThat(archive.entries[2].shareName).isNull()
        }
    }

    @Nested
    inner class Hostile {
        @Test
        fun `a symbolic link is listed, never read, never created`() {
            val archive = forged(
                Forged("lien", "../../dehors".toByteArray(), externalAttributes = Forged.LINK_ATTRIBUTES),
                Forged("lien/evade.txt", "x".toByteArray()),
            )
            assertThat(archive.entry("lien").problem).isEqualTo(ZipProblem.LINK)
            assertThrows<ZipEntryException> { archive.read(archive.entry("lien")) }
            val out = File(tmp, "out").apply { mkdirs() }
            val extraction = ZipExtraction(archive, out).also { runBlocking { it.run() } }
            assertThat(extraction.report.refused.map { it.second }).containsExactly(NotExtracted.Unreadable(ZipProblem.LINK))
            out.walkTopDown().forEach { assertThat(Files.isSymbolicLink(it.toPath())).isFalse() }
            assertThat(File(tmp.parentFile, "dehors").exists()).isFalse()
        }

        @Test
        fun `the same unix attributes from MS-DOS are no link`() {
            val archive = forged(Forged("x", "x".toByteArray(), versionMadeBy = 20, externalAttributes = Forged.LINK_ATTRIBUTES))
            assertThat(archive.entry("x").problem).isNull()
        }

        @Test
        fun `two entries of one name are each read from their own record, the second one not extracted`() {
            val archive = forged(Forged("double.txt", "premier".toByteArray()), Forged("double.txt", "second".toByteArray()))
            assertThat(archive.entries.map { archive.bytesOf(it).decodeToString() }).containsExactly("premier", "second").inOrder()
            val out = File(tmp, "out").apply { mkdirs() }
            val extraction = ZipExtraction(archive, out).also { runBlocking { it.run() } }
            assertThat(File(out, "double.txt").readText()).isEqualTo("premier")
            assertThat(extraction.report.refused.map { it.second }).containsExactly(NotExtracted.NameTaken)
        }

        /**
         * Android's shared storage folds case the Unicode way: the Kelvin sign is a `k` there, the
         * Angstrom sign an `å`, and the extraction's own key folds them alike. NTFS, where these tests
         * also run, keeps both pairs apart: they show the key at work whatever the machine's file system.
         */
        @Test
        fun `names differing by case are one name on the phone - files refused, folders merged`() {
            val archive = forged(
                Forged("Lisez-moi.txt", "1".toByteArray()),
                Forged("LISEZ-MOI.TXT", "2".toByteArray()),
                Forged("k.txt", "k".toByteArray()),
                Forged("\u212A.txt", "kelvin".toByteArray()),
                Forged("Docs/a.txt", "a".toByteArray()),
                Forged("docs/b.txt", "b".toByteArray()),
                Forged("\u212A/c.txt", "c".toByteArray()),
                Forged("k/d.txt", "d".toByteArray()),
            )
            val out = File(tmp, "out").apply { mkdirs() }
            val extraction = ZipExtraction(archive, out).also { runBlocking { it.run() } }
            assertThat(extraction.report.refused.map { it.first.name }).containsExactly("LISEZ-MOI.TXT", "\u212A.txt")
            assertThat(File(out, "Docs").list()?.sorted()).containsExactly("a.txt", "b.txt").inOrder()
            assertThat(File(out, "\u212A").list()?.sorted()).containsExactly("c.txt", "d.txt").inOrder()
            assertThat(out.list()?.sorted()).containsExactly("Docs", "Lisez-moi.txt", "k.txt", "\u212A").inOrder()
        }

        @Test
        fun `a folder and a file of one name - the second one is refused, in either order`() {
            val archive = forged(
                Forged("a", "file".toByteArray()),
                Forged("a/b.txt", "under".toByteArray()),
                Forged("c/d.txt", "under".toByteArray()),
                Forged("c", "file".toByteArray()),
                // The same by names that only case folding makes one (see above).
                Forged("k", "file".toByteArray()),
                Forged("\u212A/b.txt", "under".toByteArray()),
                Forged("\u212B/d.txt", "under".toByteArray()),
                Forged("\u00E5", "file".toByteArray()),
            )
            val out = File(tmp, "out").apply { mkdirs() }
            val extraction = ZipExtraction(archive, out).also { runBlocking { it.run() } }
            val refused = extraction.report.refused.map { it.first.name }
            assertThat(refused).containsExactly("a/b.txt", "c", "\u212A/b.txt", "\u00E5").inOrder()
            assertThat(File(out, "a").readText()).isEqualTo("file")
            assertThat(File(out, "c/d.txt").readText()).isEqualTo("under")
            assertThat(out.list()?.sorted()).containsExactly("a", "c", "k", "\u212B").inOrder()
        }

        @Test
        fun `entries sharing one block of data are refused - the better zip bomb`() {
            val kernel = Forged("noyau.bin", zeros = 1L shl 20)
            val archive = forged(
                kernel,
                Forged("copie1.bin", zeros = 1L shl 20, sharesDataOf = 0),
                Forged("copie2.bin", zeros = 1L shl 20, sharesDataOf = 0)
            )
            archive.entries.forEach { assertThat(it.problem).isEqualTo(ZipProblem.OVERLAPPING) }
        }

        @Test
        fun `a checksum that does not match is refused at the end, and leaves no file`() {
            val archive = forged(Forged("faux.txt", "contenu".toByteArray(), declaredCrc = 12345))
            assertThrows<ZipEntryException> { archive.bytesOf(archive.entry("faux.txt")) }
            val out = File(tmp, "out").apply { mkdirs() }
            ZipExtraction(archive, out).also { runBlocking { it.run() } }
            assertThat(out.list()).isEmpty()
        }

        @Test
        fun `an entry declaring more than it holds is refused at its end`() {
            val archive = forged(Forged("court.txt", "abc".toByteArray(), declaredSize = 4096))
            val refused = assertThrows<ZipEntryException> { archive.bytesOf(archive.entry("court.txt")) }
            assertThat(refused.problem).isEqualTo(ZipProblem.DAMAGED)
        }

        @Test
        fun `a record pointing at no local header is refused when read`() {
            val bytes = ZipForge.write(listOf(Forged("a.txt", "a".toByteArray())))
            bytes[0] = 0 // the local header's signature
            val archive = openArchive(File(tmp, "h.zip").apply { writeBytes(bytes) })
            assertThat(
                assertThrows<ZipEntryException> {
                    archive.read(archive.entry("a.txt"))
                }.problem
            ).isEqualTo(ZipProblem.DAMAGED)
        }

        @Test
        fun `a central directory cut short is unreadable`() {
            val bytes = ZipForge.write(listOf(Forged("a.txt", "a".toByteArray()), Forged("b.txt", "b".toByteArray())), cutDirectory = 10)
            assertThrows<UnreadableFileException> { openArchive(File(tmp, "cut.zip").apply { writeBytes(bytes) }) }
        }

        @Test
        fun `bytes prepended to the archive shift every offset - its entries still read`() {
            val bytes = ZipForge.write(listOf(Forged("a.txt", "contenu".toByteArray())), prefix = ByteArray(1000) { 0x4D })
            val archive = openArchive(File(tmp, "sfx.zip").apply { writeBytes(bytes) })
            assertThat(archive.bytesOf(archive.entry("a.txt")).decodeToString()).isEqualTo("contenu")
        }

        @Test
        fun `a folder whose name is swapped for a link during the extraction stops nothing outside`() {
            val outside = File(tmp, "dehors").apply { mkdirs() }
            val out = File(tmp, "out").apply { mkdirs() }
            val archive = forged(Forged("a/un.txt", "1".toByteArray()), Forged("a/deux.txt", "2".toByteArray()))
            val extraction = ZipExtraction(archive, out)
            runBlocking {
                extraction.run { done, _ ->
                    if (done == 1) {
                        // Another app replaces the folder just made by a link to elsewhere.
                        File(out, "a").deleteRecursively()
                        assumeTrue(runCatching { Files.createSymbolicLink(File(out, "a").toPath(), outside.toPath()) }.isSuccess)
                    }
                }
            }
            assertThat(outside.list()).isEmpty()
            assertThat(
                extraction.report.refused.map { it.first.name to it.second }
            ).containsExactly("a/deux.txt" to NotExtracted.UnsafeName)
        }

        @Test
        fun `a link already in the folder is never followed`() {
            val outside = File(tmp, "dehors").apply { mkdirs() }
            val out = File(tmp, "out").apply { mkdirs() }
            assumeTrue(runCatching { Files.createSymbolicLink(File(out, "a").toPath(), outside.toPath()) }.isSuccess)
            val extraction = ZipExtraction(forged(Forged("a/x.txt", "x".toByteArray())), out).also { runBlocking { it.run() } }
            assertThat(outside.list()).isEmpty()
            assertThat(extraction.report.refused.map { it.second }).containsExactly(NotExtracted.NameTaken)
            assertThat(Files.isSymbolicLink(File(out, "a").toPath())).isTrue()
            assertThat(Files.exists(File(out, "a/x.txt").toPath(), LinkOption.NOFOLLOW_LINKS)).isFalse()
        }
    }

    @Nested
    inner class Zip64 {
        @Test
        fun `sizes and offset in a ZIP64 block, and a ZIP64 end record, are read`() {
            val bytes = ZipForge.write(
                listOf(Forged("a.txt", "zip64".toByteArray(), zip64 = true), Forged("b.bin", ByteArray(3000), zip64 = true)),
                zip64End = true
            )
            val archive = openArchive(File(tmp, "z64.zip").apply { writeBytes(bytes) })
            assertThat(archive.bytesOf(archive.entry("a.txt")).decodeToString()).isEqualTo("zip64")
            assertThat(archive.entry("b.bin").size).isEqualTo(3000)
            assertThat(archive.bytesOf(archive.entry("b.bin"))).hasLength(3000)
        }

        @Test
        fun `a ZIP64 block too short for its fields marks its entry damaged`() {
            // The record says 0xFFFFFFFF for its sizes and offset, its block holds nothing.
            val good = ZipForge.write(listOf(Forged("a.txt", "x".toByteArray(), zip64 = true)))
            val at = good.indexOfFirst(ZipFormat.CEN_SIG) + 46 + "a.txt".length
            good[at + 2] = 0 // the block's size: 0 instead of 24
            val archive = openArchive(File(tmp, "short.zip").apply { writeBytes(good) })
            assertThat(archive.entry("a.txt").problem).isEqualTo(ZipProblem.DAMAGED)
        }

        @Test
        fun `seventy thousand entries are listed, over the 65 535 of a classic end record`() {
            val entries = (0 until 70_000).map { "d/%06d".format(it) to ByteArray(1) }
            val archive = openArchive(jdkZip(File(tmp, "many.zip"), entries, stored = true))
            assertThat(archive.entries).hasSize(70_000)
            assertThat(archive.byName.first().name).isEqualTo("d/000000")
        }

        @Test
        fun `more entries than a listing may hold are refused before they are kept, in words`() {
            val entries = (0..FileCaps.ZIP_VIEWER_ENTRIES).map { Forged("%07d".format(it), method = ZipFormat.STORED) }
            val file = File(tmp, "trop.zip").apply { writeBytes(ZipForge.write(entries)) }
            val refused = assertThrows<TooManyItemsException> { openArchive(file) }
            assertThat(refused.limit).isEqualTo(FileCaps.ZIP_VIEWER_ENTRIES)
            val words = ViewerFailure.Reason(uiText(R.string.viewer_too_many_items, FileCaps.ZIP_VIEWER_ENTRIES))
            assertThat(ViewerErrors.failureOf(refused)).isEqualTo(words)
        }

        @Test
        fun `a central directory larger than a listing may cost is refused before it is read`() {
            // An end record declaring 33 MiB of directory, in a file large enough to hold it.
            val size = FileCaps.ZIP_VIEWER_DIRECTORY + (1L shl 20)
            val end = ZipForge.write(emptyList())
            for (i in 0 until 4) end[12 + i] = (size ushr 8 * i).toByte()
            val file = File(tmp, "grand.zip")
            file.outputStream().use { stream ->
                val zeros = ByteArray(1 shl 20)
                repeat((size shr 20).toInt()) { stream.write(zeros) }
                stream.write(end)
            }
            assertThrows<TooManyItemsException> { openArchive(file) }
        }
    }

    private fun ByteArray.indexOfFirst(signature: Long): Int = (0..size - ZipFormat.U32).first {
        LittleEndian.u32(this, it) == signature
    }
}

package com.readfilestech.read_files_tech.data.viewer.zip

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.util.zip.Inflater

/**
 * `test/archive_safe_test.dart` of the 2.15.3, case by case: an entry whose header lies about its size
 * is refused all the same, and the refusal costs what the header declared, not what the data inflates
 * to. Then its signature group, which became "a file without an end record is refused, never read as an
 * empty archive" — the 2.15.3 needed `looksLikeZip` and `zipDeclaresEntries` because archive 4 returned
 * an empty archive for anything; reading the central directory first makes that impossible.
 */
class ArchiveSafeTest : ZipTestBase() {
    private fun archive(vararg entries: Forged): ZipArchive = forged(*entries)

    /** `forgeZip`: [real] bytes of zeros, the uncompressed size rewritten in both headers to [lied]. */
    private fun lying(name: String, real: Int, lied: Long) = archive(Forged(name, ByteArray(real), declaredSize = lied))

    @Test
    fun `the forged archive does lie - the declared size is not the real one`() {
        // Without this, the tests below could pass for a reason that has nothing to do with the guard.
        val zip = ZipForge.write(listOf(Forged("gros.bin", ByteArray(300_000), declaredSize = 0)))
        val item = openArchive(File(tmp, "f.zip").apply { writeBytes(zip) }).entry("gros.bin")
        assertThat(item.size).isEqualTo(0)
        // And its data really inflates to 300 000 bytes: inflated here by hand, not by the reader tested.
        val data = zip.copyOfRange(30 + "gros.bin".length, 30 + "gros.bin".length + item.data.compressedSize.toInt())
        val inflater = Inflater(true).apply { setInput(data + ByteArray(1)) }
        val out = ByteArray(400_000)
        assertThat(inflater.inflate(out)).isEqualTo(300_000)
    }

    @Test
    fun `an entry whose header lies is refused`() {
        // The old guard - `if (entry.size > max)` - let it through: 0 > 100 000 is false.
        val archive = lying("bombe.xml", real = 300_000, lied = 0)
        val refused = assertThrows<ZipEntryException> { archive.bytesOf(archive.entry("bombe.xml"), cap = 100_000) }
        assertThat(refused.problem).isEqualTo(ZipProblem.DAMAGED)
    }

    @Test
    fun `an honest entry under the cap is read as it is`() {
        val archive = archive(Forged("ok.txt", byteArrayOf(1, 2, 3, 4, 5)))
        assertThat(archive.bytesOf(archive.entry("ok.txt"), cap = 100_000)).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
    }

    @Test
    fun `an honest entry over the cap is refused without being decompressed`() {
        // Its deflated data is replaced by garbage: decompressing it would say DAMAGED, the cap says first.
        val honest = ZipForge.write(listOf(Forged("gros.bin", ByteArray(50_000))))
        val start = 30 + "gros.bin".length
        for (i in start until start + 20) honest[i] = 0xFF.toByte()
        val archive = openArchive(File(tmp, "g.zip").apply { writeBytes(honest) })
        val refused = assertThrows<FileTooLargeException> { archive.read(archive.entry("gros.bin"), cap = 1000) }
        assertThat(refused.capBytes).isEqualTo(1000)
    }

    @Test
    fun `the cap is exact - an entry right at it is read`() {
        val archive = archive(Forged("pile.bin", ByteArray(1000)))
        assertThat(archive.bytesOf(archive.entry("pile.bin"), cap = 1000)).hasLength(1000)
    }

    @Test
    fun `an empty entry is read without error`() {
        val archive = archive(Forged("vide.txt"))
        assertThat(archive.bytesOf(archive.entry("vide.txt"), cap = 1000)).isEmpty()
    }

    /** STORE entries take the other branch: no Inflater, the bytes as they are. */
    @Nested
    inner class Stored {
        private fun storedArchive(name: String, size: Int) = archive(Forged(name, pattern(size), method = ZipFormat.STORED))

        private fun pattern(size: Int) = ByteArray(size) { (it % 251).toByte() }

        @Test
        fun `an honest STORE entry is read whole`() {
            val archive = storedArchive("brut.bin", 200 * 1024)
            assertThat(archive.entry("brut.bin").data.method).isEqualTo(ZipFormat.STORED)
            assertThat(archive.bytesOf(archive.entry("brut.bin"), cap = 1024 * 1024)).isEqualTo(pattern(200 * 1024))
        }

        @Test
        fun `a STORE entry over the cap is refused`() {
            val archive = storedArchive("gros.bin", 300 * 1024)
            assertThrows<FileTooLargeException> { archive.read(archive.entry("gros.bin"), cap = 100 * 1024) }
        }

        @Test
        fun `a STORE entry right at the cap is read`() {
            val archive = storedArchive("pile.bin", 128 * 1024)
            assertThat(archive.bytesOf(archive.entry("pile.bin"), cap = 128 * 1024)).hasLength(128 * 1024)
        }

        @Test
        fun `a STORE entry declaring less than it stores is refused by its record`() {
            // Stored bytes ARE the content: 10 declared over 300 KiB stored cannot be.
            val archive = archive(Forged("x.bin", ByteArray(300 * 1024), method = ZipFormat.STORED, declaredSize = 10))
            assertThat(archive.entry("x.bin").problem).isEqualTo(ZipProblem.DAMAGED)
            assertThrows<ZipEntryException> { archive.read(archive.entry("x.bin")) }
        }
    }

    /**
     * The hole the migration to archive 4 showed (2026-08-10): the tests above check that an entry is
     * REFUSED; none checked that the memory stays BOUNDED — the S9's ANR, where the refusal worked after
     * 200 MB were allocated. Two observables here: the refusal comes on the very first read, which
     * gives at most one byte past the declared size; and it costs a fraction of a full decompression,
     * measured on this machine first (the Dart test's self-calibrated threshold).
     */
    @Test
    fun `a bomb of 256 MiB is refused without being decompressed`() {
        val bombBytes = 256L shl 20
        val zip = ZipForge.write(listOf(Forged("bombe.bin", zeros = bombBytes, declaredSize = 1024)))
        val file = File(tmp, "bombe.zip").apply { writeBytes(zip) }

        val calibration = System.nanoTime()
        val full = Inflater(true).apply { setInput(zip, 30 + "bombe.bin".length, zip.size - 30 - "bombe.bin".length) }
        val sink = ByteArray(1 shl 20)
        var inflated = 0L
        while (!full.finished() && !full.needsInput()) inflated += full.inflate(sink)
        val fullNanos = System.nanoTime() - calibration
        assertThat(inflated).isEqualTo(bombBytes)

        val archive = openArchive(file)
        val bounded = System.nanoTime()
        archive.read(archive.entry("bombe.bin"), cap = 1 shl 20).use { input ->
            // The first read already refuses: 1 025 bytes asked at most, never the 256 MiB.
            assertThrows<ZipEntryException> { input.read(ByteArray(64 * 1024)) }
        }
        val boundedNanos = System.nanoTime() - bounded
        assertThat(boundedNanos * 4).isLessThan(fullNanos)
    }

    /** `verifieSignature()` of the Dart suite: what is not an archive is refused, never shown as an empty one. */
    @Nested
    inner class Signature {
        private fun open(bytes: ByteArray) = openArchive(File(tmp, "s.zip").apply { writeBytes(bytes) })

        @Test
        fun `a file without an end record is refused, not read as an empty archive`() {
            // The fact the 2.15.3's looksLikeZip guarded against: archive 4 returned an EMPTY archive.
            assertThrows<UnreadableFileException> { open("ceci n est pas une archive".toByteArray()) }
        }

        @Test
        fun `plain text is not taken for an archive`() {
            assertThrows<UnreadableFileException> { open(ByteArray(4096) { 'a'.code.toByte() }) }
        }

        @Test
        fun `an empty or too short file is not taken for one`() {
            assertThrows<UnreadableFileException> { open(ByteArray(0)) }
            assertThrows<UnreadableFileException> { open(byteArrayOf(0x50, 0x4B, 0x03)) }
        }

        @Test
        fun `a plausible but wrong PK signature is refused`() {
            assertThrows<UnreadableFileException> { open(byteArrayOf(0x50, 0x4B, 0x01, 0x02) + ByteArray(30)) }
        }

        @Test
        fun `a real archive is read`() {
            val zip = jdkZip(File(tmp, "r.zip"), listOf("a.txt" to byteArrayOf(1, 2, 3)))
            assertThat(openArchive(zip).entries.map { it.name }).containsExactly("a.txt")
        }

        @Test
        fun `the signature alone is not enough - the case of the corpus`() {
            // `02_archive_corrompue.zip` of the 2.15.3's corpus: PK\3\4 then 4 096 bytes. It passed
            // looksLikeZip and showed as a valid, EMPTY archive on both phones (2026-08-10).
            val corrupted = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(4096) { ((it * 37 + 11) and 0xFF).toByte() }
            assertThrows<UnreadableFileException> { open(corrupted) }
        }

        @Test
        fun `a legitimately empty archive declares no entry, and is read as empty`() {
            // PK\5\6 alone: an empty archive is valid, and refusing it would refuse a correct file.
            val empty = ByteArray(22).apply {
                this[0] = 0x50
                this[1] = 0x4B
                this[2] = 0x05
                this[3] = 0x06
            }
            assertThat(open(empty).entries).isEmpty()
        }

        @Test
        fun `a real non-empty archive does declare entries`() {
            val zip = jdkZip(File(tmp, "n.zip"), listOf("a.txt" to byteArrayOf(1, 2, 3)))
            val archive = openArchive(zip)
            assertThat(archive.entries).isNotEmpty()
            assertThat(archive.bytesOf(archive.entry("a.txt"))).isEqualTo(byteArrayOf(1, 2, 3))
        }

        @Test
        fun `a legitimately EMPTY archive stays readable, a comment after it included`() {
            val withComment = ZipForge.write(emptyList(), comment = "commentaire".toByteArray())
            assertThat(open(withComment).entries).isEmpty()
        }
    }
}

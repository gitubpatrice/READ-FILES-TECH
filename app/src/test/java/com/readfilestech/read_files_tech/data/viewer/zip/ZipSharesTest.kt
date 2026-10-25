package com.readfilestech.read_files_tech.data.viewer.zip

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** Sharing one entry: extracted to the cache under its own name, in a folder of its own, purged at start. */
class ZipSharesTest : ZipTestBase() {
    private val shares by lazy { ZipShares.under(File(tmp, "cache")) }

    @Test
    fun `an entry is shared under its own name, two of one name side by side`() {
        // The 2.15.3 wrote both to cache/Relev__t_.pdf: the second replaced the first.
        val archive = forged(Forged("a/Relevé été.pdf", "un".toByteArray()), Forged("b/Relevé été.pdf", "deux".toByteArray()))
        val first = runBlocking { shares.extract(archive, archive.entries[0]) }
        val second = runBlocking { shares.extract(archive, archive.entries[1]) }

        assertThat(first.name).isEqualTo("Relevé été.pdf")
        assertThat(second.name).isEqualTo("Relevé été.pdf")
        assertThat(first.parentFile).isNotEqualTo(second.parentFile)
        assertThat(first.readText()).isEqualTo("un")
        assertThat(second.readText()).isEqualTo("deux")
        assertThat(first.parentFile.parentFile).isEqualTo(shares.folder)
    }

    @Test
    fun `an entry whose data does not hold leaves nothing behind`() {
        val archive = forged(Forged("faux.txt", "contenu".toByteArray(), declaredCrc = 7))
        assertThrows<ZipEntryException> { runBlocking { shares.extract(archive, archive.entry("faux.txt")) } }
        assertThat(shares.folder.list()).isEmpty()
    }

    @Test
    fun `the purge removes every entry shared`() {
        val archive = forged(Forged("x.txt", "x".toByteArray()))
        runBlocking { shares.extract(archive, archive.entry("x.txt")) }
        assertThat(shares.purge()).isTrue()
        assertThat(shares.folder.exists()).isFalse()
    }
}

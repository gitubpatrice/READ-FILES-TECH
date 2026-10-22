package com.readfilestech.read_files_tech.data.tools

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.convert.NothingToConvertException
import com.readfilestech.read_files_tech.data.convert.SelectionTooLargeException
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.text.CsvRows
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** The CSV tools' merge: the header once, every row as the files hold it, the dialects reconciled or refused. */
class CsvMergeTest {
    @TempDir
    lateinit var tmp: File

    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private fun csv(name: String, text: String, charset: java.nio.charset.Charset = Charsets.UTF_8, prefix: ByteArray = ByteArray(0)) =
        File(tmp, name).apply { writeBytes(prefix + text.toByteArray(charset)) }

    private fun merge(vararg files: File): Pair<CsvMerged, ByteArray> {
        val out = ByteArrayOutputStream()
        val merged = CsvMerge.write(files.toList(), out)
        return merged to out.toByteArray()
    }

    /** The merged file read back as the app reads a CSV: its BOM apart, its separator found, its rows. */
    private fun rowsOf(bytes: ByteArray): List<List<String>> {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        return CsvRows.parse(text, CsvRows.dialectOf(text).separator)
    }

    @Test
    fun `the header once, then every row of every file in order`() {
        val a = csv("a.csv", "name,qty\r\napple,1\r\npear,2\r\n")
        val b = csv("b.csv", "name,qty\nplum,3\n")
        val (merged, bytes) = merge(a, b)
        assertThat(merged).isEqualTo(CsvMerged(3, 2))
        assertThat(String(bytes, Charsets.UTF_8)).isEqualTo("name,qty\r\napple,1\r\npear,2\r\nplum,3\r\n")
    }

    @Test
    fun `the cells are written as the files hold them - no formula guard on a file's cells (D5)`() {
        val a = csv("a.csv", "n,v\nx,-5\ny,+33 6 12\nz,=1+1\nw,@SUM\n")
        val (_, bytes) = merge(a)
        assertThat(rowsOf(bytes)).containsExactly(
            listOf("n", "v"),
            listOf("x", "-5"),
            listOf("y", "+33 6 12"),
            listOf("z", "=1+1"),
            listOf("w", "@SUM"),
        ).inOrder()
    }

    @Test
    fun `dialects are reconciled - the first file's separator, UTF-8, quotes where a cell needs them`() {
        val frenchText = "nom;ville\r\nDupont;Saint-Étienne\r\n\"Martin, Paul\";\"Lyon\r\nCentre\"\r\n"
        val french = csv("fr.csv", frenchText, TextEncoding.WINDOWS_1252.charset)
        val other = csv("other.csv", "nom|ville\nDurand|Nîmes \"sud\"\n")
        val (merged, bytes) = merge(french, other)
        assertThat(merged.rows).isEqualTo(3)
        // A Windows-1252 first file: the merge carries a BOM, so that Excel reads its accents.
        assertThat(bytes.copyOf(3)).isEqualTo(bom)
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        assertThat(CsvRows.dialectOf(text).separator).isEqualTo(';')
        assertThat(CsvRows.parse(text, ';')).containsExactly(
            listOf("nom", "ville"),
            listOf("Dupont", "Saint-Étienne"),
            listOf("Martin, Paul", "Lyon\r\nCentre"),
            listOf("Durand", "Nîmes \"sud\""),
        ).inOrder()
    }

    @Test
    fun `a first file in UTF-8 without a BOM gives a merge without one, as the 2_15_3`() {
        val (_, bytes) = merge(csv("a.csv", "a,b\n1,2\n"), csv("b.csv", "a,b\n3,4\n", prefix = bom))
        assertThat(String(bytes, Charsets.UTF_8)).isEqualTo("a,b\r\n1,2\r\n3,4\r\n")
    }

    @Test
    fun `the same columns in another order are put back in the first file's order`() {
        val a = csv("a.csv", "id,name,city\n1,Ann,Paris\n")
        val b = csv("b.csv", "city,id,name\nLyon,2,Bob\nNice,3,Cy,extra\n")
        val (_, bytes) = merge(a, b)
        assertThat(rowsOf(bytes)).containsExactly(
            listOf("id", "name", "city"),
            listOf("1", "Ann", "Paris"),
            listOf("2", "Bob", "Lyon"),
            listOf("3", "Cy", "Nice", "extra"),
        ).inOrder()
    }

    @Test
    fun `other columns refuse the merge, naming the file`() {
        val a = csv("a.csv", "id,name\n1,Ann\n")
        val b = csv("b.csv", "id,email\n2,b@x.org\n")
        val error = assertThrows<CsvMergeException> { merge(a, b) }
        assertThat(error.name).isEqualTo("b.csv")
        assertThat(error.reason).isEqualTo(CsvMergeException.Reason.OTHER_COLUMNS)
        // A name twice cannot be put in order either.
        val twice = assertThrows<CsvMergeException> { merge(csv("c.csv", "x,x\n1,2\n"), csv("d.csv", "x,y\n1,2\n")) }
        assertThat(twice.reason).isEqualTo(CsvMergeException.Reason.OTHER_COLUMNS)
    }

    @Test
    fun `trailing empty header cells, empty rows and empty files do not count`() {
        val a = csv("a.csv", "a,b,\n1,2,\n,,\n\n")
        val empty = csv("empty.csv", "")
        val blank = csv("blank.csv", ";;;\n\n")
        val b = csv("b.csv", "a,b\n3,4\n")
        val (merged, bytes) = merge(a, empty, blank, b)
        assertThat(merged).isEqualTo(CsvMerged(2, 2))
        assertThat(String(bytes, Charsets.UTF_8)).isEqualTo("a,b,\r\n1,2,\r\n3,4\r\n")
    }

    @Test
    fun `no row in any file is nothing to merge`() {
        assertThrows<NothingToConvertException> { merge(csv("a.csv", ""), csv("b.csv", ",,\n")) }
    }

    @Test
    fun `the caps - a file too large, all of them too large, a cell or a row too wide`() {
        val huge = File(tmp, "huge.csv")
        RandomAccessFile(huge, "rw").use { it.setLength(FileCaps.CSV_FILE + 1) }
        val tooLarge = assertThrows<CsvMergeException> { merge(csv("a.csv", "a\n1\n"), huge) }
        assertThat(tooLarge.reason).isEqualTo(CsvMergeException.Reason.UNREADABLE)
        assertThat(tooLarge.cause).isInstanceOf(FileTooLargeException::class.java)
        val bigs = (1..4).map { index ->
            File(tmp, "big$index.csv").also { file ->
                RandomAccessFile(file, "rw").use { it.setLength(FileCaps.CSV_FILE - 1) }
            }
        }
        assertThrows<SelectionTooLargeException> { CsvMerge.write(bigs, ByteArrayOutputStream()) }
        val longCell = csv("cell.csv", "a\n" + "x".repeat(FileCaps.CSV_CELL_CHARS + 1) + "\n")
        val cell = assertThrows<CsvMergeException> { merge(longCell) }
        assertThat(cell.reason).isEqualTo(CsvMergeException.Reason.CELL_TOO_LONG)
        val wide = assertThrows<CsvMergeException> { merge(csv("wide.csv", "a\n" + ",".repeat(FileCaps.CSV_COLUMNS) + "x\n")) }
        assertThat(wide.reason).isEqualTo(CsvMergeException.Reason.TOO_MANY_COLUMNS)
        // Exactly the widest row a sheet holds goes through.
        val widest = merge(csv("widest.csv", "a\n" + ",".repeat(FileCaps.CSV_COLUMNS - 1) + "x\n")).first
        assertThat(widest.rows).isEqualTo(1)
    }

    @Test
    fun `a cancellation through the progress stops the merge`() {
        val a = csv("a.csv", "a\n" + "1\n".repeat(5000))
        assertThrows<CancellationException> {
            CsvMerge.write(listOf(a), ByteArrayOutputStream()) { if (it > 0f && it < 1f) throw CancellationException("stop") }
        }
    }

    @Test
    fun `hostile files - binary bytes and a wrong extension are read as text, or refused in words`() {
        val noise = File(tmp, "noise.csv").apply { writeBytes(ByteArray(100_000) { (it * 37 + 11).toByte() }) }
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val picture = File(tmp, "photo.csv").apply { writeBytes(png) }
        assertThat(merge(noise).first.files).isEqualTo(1)
        val refused = assertThrows<CsvMergeException> { merge(noise, picture) }
        assertThat(refused.name).isEqualTo("photo.csv")
        assertThat(refused.reason).isEqualTo(CsvMergeException.Reason.OTHER_COLUMNS)
    }
}

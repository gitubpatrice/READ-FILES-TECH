package com.readfilestech.read_files_tech.data.editor

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.text.TextEncoding
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import kotlin.random.Random

class CsvEditsTest {
    @TempDir
    lateinit var dir: File

    private val cp1252: Charset = Charset.forName("windows-1252")
    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private fun edits(bytes: ByteArray) = runBlocking { CsvEdits.of(bytes) }

    private fun edits(text: String) = edits(text.toByteArray())

    private fun saved(edits: CsvEdits): ByteArray = ByteArrayOutputStream().also(edits::writeTo).toByteArray()

    private fun savedText(edits: CsvEdits, charset: Charset = Charsets.UTF_8) = String(saved(edits), charset)

    private fun corpus(): List<File> =
        File("../tools/corpus/csv").listFiles { file -> file.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }.also {
            check(it.size >= 7) { "corpus missing: ${File("../tools/corpus/csv").absolutePath}" }
        }

    /** The plan's oracle (D5): a file saved without a change is the same file, byte for byte. */
    @Test
    fun `a file saved without a change is the same file, byte for byte`() {
        val texts = listOf(
            "a,b,c\r\n1,2,3\r\n",
            "a;b\n1;2",
            "a,b\r1,2\r",
            "sep=;\r\na;b\r\n1;2\r\n",
            "\n\na,b\n\n1,2\n;;\n,\n\n",
            "a,b\n\"5\" screen\",x\n\"open,y\n",
            "a\tb\n\" padded \"\t  spaced  \n",
            "x",
        )
        val files = texts.map { it.toByteArray() } + listOf(
            utf8Bom + "a;b\r\n\"x\r\ny\";2\r\n".toByteArray(),
            utf16LeBom + "a;b\r\n1;é\r\n".toByteArray(Charsets.UTF_16LE),
            utf16BeBom + "a;b\n1;2".toByteArray(Charsets.UTF_16BE),
            utf16LeBom + "a;b\r\n".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0x41),
            "Ville;Montant\r\nÉvry;-5\r\n+33 6;=A1\r\n".toByteArray(cp1252),
            "a,é".toByteArray().copyOf(4),
            Random(42).nextBytes(100_000),
            ByteArray(0),
        ) + corpus().map { it.readBytes() }
        files.forEach { bytes ->
            val edits = edits(bytes)
            assertThat(edits.modified).isFalse()
            assertThat(saved(edits)).isEqualTo(bytes)
        }
    }

    @Test
    fun `one cell changed changes that cell's bytes only`() {
        val edits = edits("Nom;Ville\r\nZoé;Paris\r\n\r\nBob;\"Lyon; Rhône\"\r\n;;\r\n")
        assertThat(edits.set(1, 1, "Évry")).isEqualTo(CellChange.CHANGED)
        assertThat(edits.modified).isTrue()
        assertThat(savedText(edits)).isEqualTo("Nom;Ville\r\nZoé;Évry\r\n\r\nBob;\"Lyon; Rhône\"\r\n;;\r\n")
    }

    @Test
    fun `a quoted cell stays quoted, a new text is quoted when it needs to be`() {
        val edits = edits("a,b\n\"x\",y\n")
        edits.set(1, 0, "plain")
        edits.set(1, 1, "with, comma")
        assertThat(savedText(edits)).isEqualTo("a,b\n\"plain\",\"with, comma\"\n")
        edits.set(1, 1, "say \"hi\"\nnext")
        assertThat(savedText(edits)).isEqualTo("a,b\n\"plain\",\"say \"\"hi\"\"\nnext\"\n")
        assertThat(edits.cells(1, 0, 2, 100)).containsExactly("plain", "say \"hi\"\nnext").inOrder()
    }

    @Test
    fun `a cell typed back to its text is no change`() {
        val text = "a,b\n1,2\n"
        val edits = edits(text)
        edits.set(1, 1, "9")
        edits.set(1, 1, "2")
        assertThat(edits.modified).isFalse()
        assertThat(savedText(edits)).isEqualTo(text)
        assertThat(edits.set(1, 0, "1")).isEqualTo(CellChange.UNCHANGED)
    }

    /** D5: the 2.15.3 put a `'` before `-5`, `+33…` and `=A1` of the file; the guard is for what the user types. */
    @Test
    fun `the formula guard is for what the user types, never for the file`() {
        val edits = edits("a,b\n-5,=A1\n+33612,@x\n")
        assertThat(edits.set(1, 1, "=A1")).isEqualTo(CellChange.UNCHANGED)
        assertThat(edits.set(2, 1, "=HYPERLINK(1)")).isEqualTo(CellChange.CHANGED)
        assertThat(edits.editable(2, 1)).isEqualTo(CellText.Editable("'=HYPERLINK(1)"))
        assertThat(savedText(edits)).isEqualTo("a,b\n-5,=A1\n+33612,'=HYPERLINK(1)\n")
    }

    @Test
    fun `a Windows-1252 file stays in Windows-1252, and refuses what it cannot hold`() {
        val edits = edits("Ville;Montant\r\nÉvry;3\r\n".toByteArray(cp1252))
        edits.chosenEncoding = TextEncoding.WINDOWS_1252
        assertThat(edits.set(1, 0, "Évry €")).isEqualTo(CellChange.CHANGED)
        assertThat(saved(edits)).isEqualTo("Ville;Montant\r\nÉvry €;3\r\n".toByteArray(cp1252))
        assertThat(edits.set(1, 1, "東京")).isEqualTo(CellChange.UNWRITABLE)
        assertThat(edits.editable(1, 1)).isEqualTo(CellText.Editable("3"))
    }

    @Test
    fun `a UTF-16 file keeps its byte order mark and its encoding`() {
        val edits = edits(utf16LeBom + "a;b\r\n1;2\r\n".toByteArray(Charsets.UTF_16LE))
        edits.set(1, 1, "é;x")
        assertThat(saved(edits)).isEqualTo(utf16LeBom + "a;b\r\n1;\"é;x\"\r\n".toByteArray(Charsets.UTF_16LE))
    }

    @Test
    fun `rows deleted and added, the empty lines and the line breaks kept`() {
        val edits = edits("h1,h2\r\n\r\nr1,x\r\nr2,y\r\n")
        edits.deleteRow(1)
        edits.insertRow(0)
        edits.set(1, 0, "new")
        assertThat(savedText(edits)).isEqualTo("h1,h2\r\nnew,\r\n\r\nr2,y\r\n")
        assertThat(edits.rowCount).isEqualTo(3)
    }

    @Test
    fun `a row added at the end of a file without a final line break`() {
        val edits = edits("a,b\n1,2")
        edits.insertRow(1)
        assertThat(savedText(edits)).isEqualTo("a,b\n1,2\n,")
        val ended = edits("a,b\n1,2\n")
        ended.insertRow(1)
        assertThat(savedText(ended)).isEqualTo("a,b\n1,2\n,\n")
        val single = edits("a,b")
        single.insertRow(0)
        assertThat(savedText(single)).isEqualTo("a,b\r\n,")
    }

    @Test
    fun `the header is never deleted, nor the last column`() {
        val edits = edits("a\n1\n")
        assertThat(edits.canDeleteRow(0)).isFalse()
        assertThrows<IllegalArgumentException> { edits.deleteRow(0) }
        assertThat(edits.canDeleteColumn).isFalse()
        assertThrows<IllegalStateException> { edits.deleteColumn(0) }
    }

    /** D4: a short row threw on a tap, and adding then deleting a column threw — here they are rows like the others. */
    @Test
    fun `rows of any length`() {
        val text = "h1,h2,h3\n1,2,3\nshort\n"
        val edits = edits(text)
        assertThat(edits.cells(2, 0, 3, 100)).containsExactly("short", "", "").inOrder()
        assertThat(edits.editable(2, 2)).isEqualTo(CellText.Editable(""))
        edits.insertColumn(3)
        assertThat(savedText(edits)).isEqualTo("h1,h2,h3,\n1,2,3\nshort\n")
        edits.deleteColumn(3)
        assertThat(saved(edits)).isEqualTo(text.toByteArray())
        edits.set(2, 2, "x")
        assertThat(savedText(edits)).isEqualTo("h1,h2,h3\n1,2,3\nshort,,x\n")
    }

    @Test
    fun `a column inserted or deleted, short rows untouched`() {
        val inserted = edits("h1,h2,h3\n1,2,3\nshort\n")
        inserted.insertColumn(1)
        inserted.set(0, 1, "new")
        assertThat(inserted.cells(0, 0, 4, 100)).containsExactly("h1", "new", "h2", "h3").inOrder()
        assertThat(savedText(inserted)).isEqualTo("h1,new,h2,h3\n1,,2,3\nshort\n")
        val deleted = edits("h1,h2,h3\n1,\"2\",3\nshort\n")
        deleted.set(1, 2, "9")
        deleted.deleteColumn(1)
        assertThat(savedText(deleted)).isEqualTo("h1,h3\n1,9\nshort\n")
    }

    @Test
    fun `a cell longer than a cell may be is not edited, nor cut`() {
        val long = "x".repeat(FileCaps.CSV_CELL_CHARS + 1)
        val text = "a,b\n$long,1\n"
        val edits = edits(text)
        assertThat(edits.editable(1, 0)).isEqualTo(CellText.TooLong)
        assertThat(edits.set(1, 0, "y")).isEqualTo(CellChange.TOO_LONG)
        assertThat(edits.set(1, 1, "z".repeat(FileCaps.CSV_CELL_CHARS + 1))).isEqualTo(CellChange.TOO_LONG)
        edits.set(1, 1, "2")
        assertThat(savedText(edits)).isEqualTo("a,b\n$long,2\n")
    }

    @Test
    fun `the rows past the cap are written back as they were`() {
        val text = "h\n" + "x\n".repeat(FileCaps.CSV_ROWS)
        val edits = edits(text)
        assertThat(edits.rowsCut).isTrue()
        assertThat(edits.rowCount).isEqualTo(FileCaps.CSV_ROWS)
        assertThat(edits.canAddRow).isFalse()
        edits.set(1, 0, "y")
        assertThat(savedText(edits)).isEqualTo("h\ny\n" + "x\n".repeat(FileCaps.CSV_ROWS - 1))
    }

    @Test
    fun `the columns past the cap are written back as they were`() {
        val header = (0..FileCaps.CSV_COLUMNS).joinToString(",")
        val edits = edits("$header\n1\n")
        assertThat(edits.columnsCut).isTrue()
        assertThat(edits.canAddColumn).isFalse()
        edits.set(0, 0, "Z")
        assertThat(savedText(edits)).isEqualTo("Z" + header.removePrefix("0") + "\n1\n")
    }

    @Test
    fun `an empty file, or one of empty lines, has nothing to edit`() {
        listOf("", "\r\n\r\n", ";;;\n").forEach { text ->
            val edits = edits(text)
            assertThat(edits.isEmpty).isTrue()
            assertThat(edits.canAddRow).isFalse()
            assertThat(edits.canAddColumn).isFalse()
            assertThat(savedText(edits)).isEqualTo(text)
        }
    }

    /** Claude review R20: with fields past the cap, a column deleted then one added shifted them under other columns. */
    @Test
    fun `the fields past the column cap stay under their columns`() {
        val cap = FileCaps.CSV_COLUMNS
        val file = (0..cap).joinToString(",") { "h$it" } + "\n" + (0..cap).joinToString(",") { "d$it" } + "\n"
        val edits = edits(file)
        edits.deleteColumn(0)
        edits.insertColumn(edits.columnCount)
        val header = (1 until cap).joinToString(",") { "h$it" } + ",,h$cap"
        val row = (1 until cap).joinToString(",") { "d$it" } + ",,d$cap"
        assertThat(savedText(edits)).isEqualTo("$header\n$row\n")
    }

    /** The same, every column of the file deleted and two added: the fields past the cap still after both. */
    @Test
    fun `the fields past the column cap stay after the columns added, every other deleted`() {
        val cap = FileCaps.CSV_COLUMNS
        val file = (0..cap).joinToString(",") { "h$it" } + "\n" + (0..cap).joinToString(",") { "d$it" } + "\n"
        val bare = edits(file)
        repeat(2) {
            bare.deleteColumn(bare.columnCount - 1)
            bare.insertColumn(0)
        }
        while (bare.columnCount > 2) bare.deleteColumn(2)
        assertThat(savedText(bare)).isEqualTo(",,h$cap\n,,d$cap\n")
    }

    /** Claude review R20: UTF-16 without a byte order mark read a byte at a time — an edit shifted the rest of the file. */
    @Test
    fun `a text holding NUL bytes is shown, not edited`() {
        val bytes = "a,b\r\n1,2\r\n".toByteArray(Charsets.UTF_16LE)
        val edits = edits(bytes)
        assertThat(edits.readOnly).isEqualTo(ReadOnly.NUL_BYTES)
        assertThat(edits.canAddRow).isFalse()
        assertThat(edits.canDeleteColumn).isFalse()
        assertThat(edits.set(1, 0, "x")).isEqualTo(CellChange.READ_ONLY)
        assertThat(saved(edits)).isEqualTo(bytes)
    }

    /** Claude review R20: an ASCII file got UTF-8 that Excel reads as `HÃ©lÃ¨ne`; the user chooses, once. */
    @Test
    fun `a file that does not say its encoding asks it at the first accent`() {
        val text = "a;b\r\n1;2\r\n"
        val ascii = edits(text)
        assertThat(ascii.encodingStated).isFalse()
        assertThat(ascii.encodingChoices).containsExactly(TextEncoding.UTF_8, TextEncoding.WINDOWS_1252).inOrder()
        assertThat(ascii.set(1, 0, "x")).isEqualTo(CellChange.CHANGED)
        assertThat(ascii.set(1, 1, "Hélène")).isEqualTo(CellChange.ENCODING_UNCHOSEN)
        ascii.chosenEncoding = TextEncoding.WINDOWS_1252
        assertThat(ascii.set(1, 1, "Hélène")).isEqualTo(CellChange.CHANGED)
        assertThat(saved(ascii)).isEqualTo("a;b\r\nx;Hélène\r\n".toByteArray(cp1252))
        val utf8 = edits(text)
        utf8.chosenEncoding = TextEncoding.UTF_8
        utf8.set(1, 1, "Hélène")
        assertThat(savedText(utf8)).isEqualTo("a;b\r\n1;Hélène\r\n")
        val windows = edits("a;b\r\nÉvry;2\r\n".toByteArray(cp1252))
        assertThat(windows.encodingChoices).containsExactly(TextEncoding.WINDOWS_1252)
        assertThrows<IllegalArgumentException> { windows.chosenEncoding = TextEncoding.UTF_8 }
        val stated = edits("a;b\r\nÉvry;2\r\n")
        assertThat(stated.encodingStated).isTrue()
        assertThat(stated.set(1, 1, "Hélène")).isEqualTo(CellChange.CHANGED)
    }

    /** Claude review R20: an MS-DOS or Mac file read as Windows-1252 — a typed `é` would read `Ú` or `È` there. */
    @Test
    fun `a file of an MS-DOS or Mac code page is shown, not edited`() {
        assertThat(edits(File("../tools/corpus/csv-editor/excel-msdos.csv").readBytes()).readOnly).isEqualTo(ReadOnly.DOS_CODE_PAGE)
        assertThat(edits(File("../tools/corpus/csv-editor/excel-mac-cr.csv").readBytes()).readOnly).isEqualTo(ReadOnly.DOS_CODE_PAGE)
        assertThat(edits(File("../tools/corpus/csv/excel-csv-1252.csv").readBytes()).readOnly).isNull()
        assertThat(edits("Nom;Note\r\nl’homme;cœur\r\n".toByteArray(cp1252)).readOnly).isNull()
    }

    /** Claude review R21: dashes and `š ž` of Windows-1252 made a valid file read-only — after its own save too. */
    @Test
    fun `Windows-1252 dashes and Central European letters between letters stay editable`() {
        assertThat(edits("Trajet;Ville\r\nParis–Lyon;Košice\r\nwell—known;Žižek\r\n".toByteArray(cp1252)).readOnly).isNull()
        // Ellipsis, closing quote and trademark at a word's end: ordinary Windows-1252 too.
        assertThat(edits("a;b\r\nwait…;“Bonjour”\r\nBrand™;x\r\n".toByteArray(cp1252)).readOnly).isNull()
    }

    /** Claude review R21: an MS-DOS accent at the end of a word (`Payé;`) was not seen — only between letters. */
    @Test
    fun `an MS-DOS accent at the end of a word, before a separator or the end, is seen`() {
        val cp850 = charset("IBM850")
        assertThat(edits("Etat;Lieu\r\nPayé;Ici\r\n".toByteArray(cp850)).readOnly).isEqualTo(ReadOnly.DOS_CODE_PAGE)
        assertThat(edits("Etat\r\n\"Payé\"\r\n".toByteArray(cp850)).readOnly).isEqualTo(ReadOnly.DOS_CODE_PAGE)
        assertThat(edits("Etat\r\ncafé".toByteArray(cp850)).readOnly).isEqualTo(ReadOnly.DOS_CODE_PAGE)
    }

    /** Claude review R20: U+FFFD shown for bytes the encoding lacks was written back in their place. */
    @Test
    fun `a cell whose bytes would not come back is not edited`() {
        val odd = edits(utf16LeBom + "a;b\r\n1;2".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0x41))
        assertThat(odd.editable(1, 1)).isEqualTo(CellText.Undecodable)
        assertThat(odd.set(1, 1, "3")).isEqualTo(CellChange.UNDECODABLE)
        assertThat(odd.editable(1, 0)).isEqualTo(CellText.Editable("1"))
        val windows = edits("a;b\r\nÉ;".toByteArray(cp1252) + byteArrayOf(0x81.toByte()) + "\r\n".toByteArray())
        assertThat(windows.editable(1, 1)).isEqualTo(CellText.Undecodable)
        val cut = edits("a,é\n1,".toByteArray() + byteArrayOf(0xC3.toByte()))
        assertThat(cut.editable(1, 1)).isEqualTo(CellText.Undecodable)
    }

    /** Claude review R20: a row writing nothing before its line break is an empty line, which no reader counts. */
    @Test
    fun `an empty row of one column is written as an empty field`() {
        val edits = edits("h\nx\ny\n")
        edits.set(1, 0, "")
        edits.insertRow(2)
        assertThat(savedText(edits)).isEqualTo("h\n\"\"\ny\n\"\"\n")
    }

    /** Claude review R20: without `sep=`, an unquoted `,` typed in a `;` file could tip the next reading. */
    @Test
    fun `a typed text holding a separator is quoted when the file does not name its own`() {
        val guessed = edits("Nom;Ville\r\nZoé;Paris\r\n")
        guessed.set(0, 0, "Nom, prénom")
        assertThat(savedText(guessed)).isEqualTo("\"Nom, prénom\";Ville\r\nZoé;Paris\r\n")
        val named = edits("sep=;\r\nNom;Ville\r\nZoé;Paris\r\n")
        named.set(0, 0, "Nom, prénom")
        assertThat(savedText(named)).isEqualTo("sep=;\r\nNom, prénom;Ville\r\nZoé;Paris\r\n")
    }

    @Test
    fun `a file over the CSV cap is refused before it is read`() {
        val file = File(dir, "huge.csv")
        RandomAccessFile(file, "rw").use { it.setLength(FileCaps.CSV_FILE + 1) }
        assertThrows<FileTooLargeException> { runBlocking { CsvEdits.read(StorageFileSource(file)) } }
    }
}

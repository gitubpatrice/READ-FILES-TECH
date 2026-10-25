package com.readfilestech.read_files_tech.data.viewer

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.viewer.document.DocumentLimits
import com.readfilestech.read_files_tech.data.viewer.document.DocumentText
import com.readfilestech.read_files_tech.data.viewer.document.DocumentXml
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The document viewer's reading (`DocumentText`): first the 2.15.3's suite, case by case
 * (`test/text_extraction_service_test.dart` — its functions took a String of XML, these cases go through
 * a real archive and the real parser), then what the port adds: the format told by the content, the
 * bounds a hostile file cannot move, and the 2.15.3's defects, each with the case that showed it.
 */
class DocumentTextTest {
    @TempDir
    lateinit var dir: File

    // ---------------------------------------------------------------------------------------------
    // The 2.15.3's suite — docxXmlToPlainText
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `dart - extracts a simple paragraph`() {
        assertThat(text(docx("<w:p><w:r><w:t>Hello world</w:t></w:r></w:p>"))).isEqualTo("Hello world")
    }

    /** The 2.15.3's regression: `split('')` cut by character and wrote an empty .txt. */
    @Test
    fun `dart - paragraphs are separate lines`() {
        val out = text(docx(paragraphs("Première", "Deuxième", "Troisième")))
        assertThat(out).isEqualTo("Première\nDeuxième\nTroisième")
    }

    @Test
    fun `dart - xml space preserve keeps the space between two texts`() {
        val xml = """<w:p><w:r><w:t xml:space="preserve">Mot 1 </w:t><w:t>Mot 2</w:t></w:r></w:p>"""
        assertThat(text(docx(xml))).isEqualTo("Mot 1 Mot 2")
    }

    @Test
    fun `dart - w br is a line feed and w tab a tab`() {
        val xml = "<w:p><w:r><w:t>Avant</w:t><w:br/><w:t>Après</w:t><w:tab/><w:t>Indenté</w:t></w:r></w:p>"
        assertThat(text(docx(xml))).isEqualTo("Avant\nAprès\tIndenté")
    }

    // decodeXmlEntities — the parser decodes now; the cases stay, inside a text.

    @Test
    fun `dart - the five standard entities`() {
        val out = text(docx(paragraphs("a &lt; b &amp; c &gt; d &quot;e&quot; f &apos;g&apos;")))
        assertThat(out).isEqualTo("a < b & c > d \"e\" f 'g'")
    }

    /** Decoding `&amp;` first turned `&amp;lt;` into `<` (the copy the 2.15.3 screen carried before A-P1b). */
    @Test
    fun `dart - an escaped entity is decoded once`() {
        assertThat(text(docx(paragraphs("&amp;lt;")))).isEqualTo("&lt;")
    }

    @Test
    fun `dart - decimal and hexadecimal character references`() {
        assertThat(text(docx(paragraphs("caf&#233;")))).isEqualTo("café")
        assertThat(text(docx(paragraphs("caf&#xE9;")))).isEqualTo("café")
        assertThat(text(docx(paragraphs("a&#10;b")))).isEqualTo("a\nb")
        assertThat(text(docx(paragraphs("&#x1F600;")))).isEqualTo("😀")
    }

    /**
     * Changed: the 2.15.3's regex left `&#abc;` as it was. XML forbids it — Word never writes it — and a
     * parser refuses the part: the file is said damaged, instead of shown as a guess.
     */
    @Test
    fun `dart - a malformed reference is refused as damaged, no longer shown as it was`() {
        assertThrows<UnreadableFileException> { open(docx(paragraphs("foo &#abc; bar"))) }
    }

    // UTF-8 and characters beyond ASCII

    @Test
    fun `dart - French accents are kept`() {
        val out = text(docx(paragraphs("Été à la française : éàèçùôîïëœ — c'était parfait !")))
        assertThat(out).contains("Été à la française")
        assertThat(out).contains("éàèçùôîïëœ")
    }

    @Test
    fun `dart - emoji and CJK are kept`() {
        val out = text(docx(paragraphs("Hello 🌍 こんにちは 你好 안녕")))
        listOf("🌍", "こんにちは", "你好", "안녕").forEach { assertThat(out).contains(it) }
    }

    // ---------------------------------------------------------------------------------------------
    // The 2.15.3's suite — odtXmlToPlainText
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `dart - text p paragraphs`() {
        val out = text(odt("<text:p>Premier paragraphe</text:p><text:p>Second paragraphe</text:p>"))
        assertThat(out).isEqualTo("Premier paragraphe\nSecond paragraphe")
    }

    @Test
    fun `dart - inner formatting elements leave their text`() {
        val out = text(odt("""<text:p>Du <text:span text:style-name="T1">gras</text:span> ici</text:p>"""))
        assertThat(out).isEqualTo("Du gras ici")
    }

    @Test
    fun `dart - numeric references, which the old copy did not decode`() {
        assertThat(text(odt("<text:p>caf&#233; cr&#xE8;me</text:p>"))).isEqualTo("café crème")
    }

    @Test
    fun `dart - an escaped entity is decoded once, OpenDocument`() {
        assertThat(text(odt("<text:p>&amp;lt; reste du texte</text:p>"))).isEqualTo("&lt; reste du texte")
    }

    @Test
    fun `dart - a carriage return reference is a line feed`() {
        val out = text(odt("<text:p>ligne un&#xD;ligne deux</text:p>"))
        assertThat(out).isEqualTo("ligne un\nligne deux")
        assertThat(out).doesNotContain("\r")
    }

    @Test
    fun `dart - blank paragraphs are left out`() {
        assertThat(text(odt("<text:p>A</text:p><text:p>   </text:p><text:p>B</text:p>"))).isEqualTo("A\nB")
    }

    @Test
    fun `dart - headings too, in the document's order`() {
        val xml = """<text:h text:outline-level="1">Titre premier</text:h><text:p>Un paragraphe.</text:p>""" +
            """<text:h text:outline-level="2">Sous-titre</text:h>"""
        assertThat(text(odt(xml))).isEqualTo("Titre premier\nUn paragraphe.\nSous-titre")
    }

    @Test
    fun `dart - a document of headings only is not empty`() {
        val document = open(odt("<text:h>Plan</text:h><text:h>Chapitre 1</text:h>"))
        assertThat(document.hasText).isTrue()
        assertThat(text(document)).isEqualTo("Plan\nChapitre 1")
    }

    @Test
    fun `dart - text line-break is a line feed, text tab a tab`() {
        val xml = "<text:p>ligne un<text:line-break/>ligne deux</text:p><text:p>col1<text:tab/>col2</text:p>"
        assertThat(text(odt(xml))).isEqualTo("ligne un\nligne deux\ncol1\tcol2")
    }

    @Test
    fun `dart - accents and emoji, OpenDocument`() {
        val out = text(odt("<text:p>Été 🌍 你好</text:p>"))
        listOf("Été", "🌍", "你好").forEach { assertThat(out).contains(it) }
    }

    // ---------------------------------------------------------------------------------------------
    // The 2.15.3's suite — complexity on hostile input. The 2.15.3's regex took 5 s (ODT) and 19 s
    // (DOCX) on these; its fix returned nothing, quickly. A parser refuses them, as quickly: tags that
    // never close are not XML.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `dart - thousands of text p never closed are refused, quickly`() {
        val file = odt("<text:p>".repeat(20_000))
        // At the depth cap, where the 257th opens: not at the end of the part, by the parser.
        assertWithin(BUDGET_MILLIS) { assertThat(assertThrows<UnreadableFileException> { open(file) }.cause).isNull() }
    }

    @Test
    fun `dart - thousands of w t never closed are refused, quickly`() {
        val file = docx("<w:p><w:r>" + "<w:t>".repeat(20_000))
        assertWithin(BUDGET_MILLIS) { assertThat(assertThrows<UnreadableFileException> { open(file) }.cause).isNull() }
    }

    @Test
    fun `dart - thousands of orphan closing tags are refused, quickly`() {
        val file = odt("</text:p>".repeat(20_000))
        assertWithin(BUDGET_MILLIS) { assertThrows<UnreadableFileException> { open(file) } }
    }

    /** What gives the three above their sense: a large legitimate document stays fast. */
    @Test
    fun `dart - a large legitimate document stays fast`() {
        val file = odt("<text:p>une ligne de texte</text:p>".repeat(20_000))
        assertWithin(BUDGET_MILLIS) {
            assertThat(open(file).document.rowCount).isEqualTo(20_000)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The format, told by the content
    // ---------------------------------------------------------------------------------------------

    /** A .doc of Word 97, and a Word document encrypted with a password, are OLE files, whatever their name. */
    @Test
    fun `an OLE file is a format not opened, whatever its extension`() {
        listOf("old.doc", "renamed.docx", "renamed.odt", "renamed.odp").forEach { name ->
            val file = File(dir, name).apply { writeBytes(OLE + ByteArray(504)) }
            assertThrows<UnsupportedFormatException> { open(file) }
        }
    }

    /** The whole signature, not the 2.15.3's first 4 bytes: anything else is a damaged file, not a Word 97 one. */
    @Test
    fun `half an OLE signature is damaged, not Word 97`() {
        val file = File(dir, "half.doc").apply { writeBytes(OLE.copyOf(4) + ByteArray(508)) }
        assertThrows<UnreadableFileException> { open(file) }
    }

    @Test
    fun `random bytes, an empty file, a ZIP without a document are damaged`() {
        val random = File(dir, "random.odt").apply { writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() }) }
        assertThrows<UnreadableFileException> { open(random) }
        assertThrows<UnreadableFileException> { open(File(dir, "empty.docx").apply { writeBytes(ByteArray(0)) }) }
        assertThrows<UnreadableFileException> { open(archive("nothing.docx", "[Content_Types].xml" to "<Types/>")) }
    }

    /** The 2.15.3 chose by the name: each of these was refused for want of the other format's main part. */
    @Test
    fun `a Word document named odt and an OpenDocument named docx open`() {
        assertThat(text(docx(paragraphs("Je suis un docx"), name = "named.odt"))).isEqualTo("Je suis un docx")
        assertThat(text(odt("<text:p>Je suis un odt</text:p>", name = "named.docx"))).isEqualTo("Je suis un odt")
    }

    /** OPC names the main part in `_rels/.rels`: where Word writes it is only the usual place. */
    @Test
    fun `the main part is the one the package relationships name`() {
        val file = archive(
            "moved.docx",
            "_rels/.rels" to relationships("/word/document2.xml"),
            "word/document2.xml" to WORD_HEAD + paragraphs("Ailleurs") + WORD_TAIL,
            "word/document.xml" to WORD_HEAD + paragraphs("Pas moi") + WORD_TAIL,
        )
        assertThat(text(file)).isEqualTo("Ailleurs")
        val dangling = archive(
            "dangling.docx",
            "_rels/.rels" to relationships("word/absent.xml"),
            "word/document.xml" to WORD_HEAD + paragraphs("Ici") + WORD_TAIL,
        )
        assertThat(text(dangling)).isEqualTo("Ici")
    }

    /** An Excel file named .docx: its relationships name a workbook, not a Word document. */
    @Test
    fun `a part whose root is not a Word document is refused`() {
        val workbook = archive(
            "sheet.docx",
            "_rels/.rels" to relationships("xl/workbook.xml"),
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"/>""",
        )
        assertThrows<UnreadableFileException> { open(workbook) }
        val wrongRoot = archive("root.docx", "word/document.xml" to """<w:body xmlns:w="$WORD_NS"/>""")
        assertThrows<UnreadableFileException> { open(wrongRoot) }
        val wrongOdf = archive("root.odt", "content.xml" to """<office:document-styles xmlns:office="$OFFICE_NS"/>""")
        assertThrows<UnreadableFileException> { open(wrongOdf) }
    }

    @Test
    fun `a damaged archive is unreadable, not an error of the storage`() {
        // Letters that do not repeat, so that the deflated data is long: bytes 100 to 300 fall inside it.
        val letters = generateSequence(7) { it * 1_103_515_245 + 12_345 }
            .take(20_000)
            .map { 'a' + ((it ushr 16) and 0xFFFF) % 26 }
        val good = docx(paragraphs(letters.joinToString(""))).readBytes()
        val cut = File(dir, "cut.docx").apply { writeBytes(good.copyOf(good.size / 2)) }
        assertThrows<UnreadableFileException> { open(cut) }
        // The deflated data of the entry overwritten: the archive's directory is intact, its content is not.
        val corrupt = good.copyOf().also { bytes -> (100 until 300).forEach { bytes[it] = 0x55 } }
        assertThrows<UnreadableFileException> { open(File(dir, "corrupt.docx").apply { writeBytes(corrupt) }) }
    }

    @Test
    fun `every extension of the 2_15_3's DocxViewerScreen opens in the document viewer`() {
        listOf("docx", "doc", "odt", "odp").forEach { extension ->
            val entry = FileEntry("/d/f.$extension", "f.$extension", false, 0, 0, isSymlink = false)
            assertThat(FileRouter.targetFor(entry)).isEqualTo(FileTarget.Internal("viewer/DOCUMENT?path=%2Fd%2Ff.$extension"))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // XML a hostile file controls
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the parser is namespace-aware and never processes a DTD`() {
        val parser = DocumentXml.parser(ByteArrayInputStream(ByteArray(0)), 10)
        // The one Android ships (copied from AOSP into the tests), not another implementation's behaviour.
        assertThat(parser.javaClass.name).isEqualTo("com.android.org.kxml2.io.KXmlParser")
        assertThat(parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)).isFalse()
        assertThat(parser.getFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES)).isTrue()
    }

    /** Refused before the parser reads it — even a DOCTYPE that declares nothing, which a parser would pass. */
    @Test
    fun `a DOCTYPE is refused, the billion laughs with it`() {
        val plain = archive(
            "plain.docx",
            "word/document.xml" to withDoctype(WORD_HEAD, "<!DOCTYPE w:document []>") + paragraphs("x") + WORD_TAIL,
        )
        assertThrows<UnreadableFileException> { open(plain) }
        val laughs = (1..9).joinToString("") { level ->
            val previous = if (level == 1) "lol" else "&lol${level - 1};".repeat(10)
            """<!ENTITY lol$level "$previous">"""
        }
        val bomb = archive(
            "laughs.docx",
            "word/document.xml" to withDoctype(WORD_HEAD, "<!DOCTYPE w:document [$laughs]>") + paragraphs("&lol9;") + WORD_TAIL,
        )
        assertWithin(BUDGET_MILLIS) { assertThrows<UnreadableFileException> { open(bomb) } }
        val external = archive(
            "external.odt",
            "content.xml" to withDoctype(ODF_HEAD, """<!DOCTYPE office:document-content SYSTEM "file:///etc/hosts">""") +
                "<text:p>x</text:p>" + ODF_TAIL,
        )
        assertThrows<UnreadableFileException> { open(external) }
    }

    @Test
    fun `any other declaration in the body is refused, before the parser reads it`() {
        listOf("<!ENTITY x \"y\"><w:p/>", "<!-x--><w:p/>", "<w:p><w:r><w:t><![CDATAX[a]]></w:t></w:r></w:p>").forEach { body ->
            // Refused by the guard: no parser's exception behind it (the parser would refuse them too, later).
            assertThat(assertThrows<UnreadableFileException> { open(docx(body)) }.cause).isNull()
        }
    }

    /**
     * The guard follows the markup: a `<!DOCTYPE` inside a CDATA section or a comment is text, or nothing —
     * even after a `>`, which ends neither.
     */
    @Test
    fun `a DOCTYPE written inside CDATA or a comment is not one`() {
        val cdata = "<w:p><w:r><w:t><![CDATA[a > <!DOCTYPE x> & <b>]]></w:t></w:r></w:p>"
        assertThat(text(docx(cdata))).isEqualTo("a > <!DOCTYPE x> & <b>")
        assertThat(text(docx("<!-- a > <!DOCTYPE x> --><w:p><w:r><w:t>y</w:t></w:r></w:p>"))).isEqualTo("y")
        assertThat(text(docx("""<w:p w:x="a>b"><w:r><w:t>z</w:t></w:r></w:p>"""))).isEqualTo("z")
    }

    /** A stretch is one text or one tag: a long text and the long tag after it, or before it, do not add up. */
    @Test
    fun `a text and a tag next to it are two stretches`() {
        val limits = DocumentLimits(stretchChars = 1000)
        val textThenTag = "<w:p><w:r><w:t>" + "a".repeat(600) + "</w:t" + " ".repeat(600) + "></w:r></w:p>"
        assertThat(text(docx(textThenTag), limits)).isEqualTo("a".repeat(600))
        val tagThenText = "<w:p><w:r><w:t xml:space=\"preserve\"" + " ".repeat(600) + ">" + "b".repeat(600) + "</w:t></w:r></w:p>"
        assertThat(text(docx(tagThenText), limits)).isEqualTo("b".repeat(600))
    }

    @Test
    fun `a stretch between two tags past its cap is too large, many short ones are not`() {
        val limits = DocumentLimits(stretchChars = 1000)
        val long = assertThrows<FileTooLargeException> { open(docx(paragraphs("a".repeat(1001))), limits) }
        assertThat(long.capBytes).isEqualTo(1000)
        // 500 short paragraphs, 10 000 chars between the root's tags: each stretch ends at a tag.
        assertThat(open(docx(paragraphs(*Array(500) { "b".repeat(10) })), limits).hasText).isTrue()
        // One tag with a long attribute is one stretch too.
        assertThrows<FileTooLargeException> { open(docx("""<w:p w:x="${"c".repeat(1001)}"/>"""), limits) }
    }

    /** What the parser coalesces into one text is one stretch: CDATA holding `<`, comments between texts, `>` in a value. */
    @Test
    fun `a stretch is what the parser holds whole, whatever splits it lexically`() {
        val limits = DocumentLimits(stretchChars = 1000)
        val cdata = "<w:p><w:r><w:t><![CDATA[" + "a<".repeat(600) + "]]></w:t></w:r></w:p>"
        assertThrows<FileTooLargeException> { open(docx(cdata), limits) }
        val comments = "<w:p><w:r><w:t>" + "aaaa<!---->".repeat(200) + "</w:t></w:r></w:p>"
        assertThrows<FileTooLargeException> { open(docx(comments), limits) }
        // A `>` in a value does not end the tag: 600 chars on each side of it are one stretch of 1200.
        val quoted = """<w:p w:x="${"a".repeat(600)}>${"a".repeat(600)}"/>"""
        assertThrows<FileTooLargeException> { open(docx(quoted), limits) }
    }

    @Test
    fun `elements nested past the depth are refused, at the depth they open`() {
        val limits = DocumentLimits(depth = 8)
        // w:document, w:body, w:p, w:r, w:t: 5 levels; 3 more reach 8, 4 more pass it — all well closed.
        assertThat(text(docx(nested(3, "<w:p><w:r><w:t>ok</w:t></w:r></w:p>")), limits)).isEqualTo("ok")
        assertThrows<UnreadableFileException> { open(docx(nested(4, "<w:p><w:r><w:t>no</w:t></w:r></w:p>")), limits) }
    }

    /** What a part costs is its elements: past the bound, refused, however few bytes the file holds. */
    @Test
    fun `a part of more elements than its bound holds too many items`() {
        val limits = DocumentLimits(elements = 1000)
        // w:document, w:body and 998 empty paragraphs: the bound exactly.
        assertThat(open(docx("<w:p/>".repeat(998)), limits).hasText).isFalse()
        val refused = assertThrows<TooManyItemsException> { open(docx("<w:p/>".repeat(999)), limits) }
        assertThat(refused.limit).isEqualTo(1000)
    }

    @Test
    fun `a million nested elements are refused, quickly`() {
        val file = docx("<w:p>" + "<x>".repeat(1_000_000))
        assertWithin(BUDGET_MILLIS) { assertThrows<UnreadableFileException> { open(file) } }
    }

    // ---------------------------------------------------------------------------------------------
    // Sizes: the file, a part once decompressed, the text shown
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the bounds are the 2_15_3's caps`() {
        val limits = DocumentLimits()
        assertThat(limits.fileBytes).isEqualTo(FileCaps.DOC_ZIPPED)
        assertThat(limits.partBytes).isEqualTo(FileCaps.ZIP_ENTRY_DECOMPRESSED)
        assertThat(limits.textBytes).isEqualTo(FileCaps.TEXT_VIEWER)
        assertThat(limits.stretchChars).isEqualTo(FileCaps.XML_STRETCH_CHARS)
        assertThat(limits.depth).isEqualTo(FileCaps.XML_DEPTH)
        assertThat(limits.elements).isEqualTo(FileCaps.XML_ELEMENTS)
        assertThat(FileCaps.DOC_ZIPPED).isEqualTo(50L shl 20)
        assertThat(FileCaps.ZIP_ENTRY_DECOMPRESSED).isEqualTo(200L shl 20)
    }

    @Test
    fun `a file one byte over its cap is refused before it is read`() {
        val big = File(dir, "big.docx")
        RandomAccessFile(big, "rw").use { it.setLength(FileCaps.DOC_ZIPPED + 1) }
        val refused = assertThrows<FileTooLargeException> { open(big) }
        assertThat(refused.capBytes).isEqualTo(FileCaps.DOC_ZIPPED)
    }

    /** A few kilobytes that inflate past the cap: the decompressed bytes are counted, not the header's size. */
    @Test
    fun `a part that inflates past its cap is too large`() {
        val limits = DocumentLimits(partBytes = 100_000)
        val bomb = docx("<w:p/>".repeat(20_000))
        assertThat(bomb.length()).isLessThan(10_000)
        val refused = assertThrows<FileTooLargeException> { open(bomb, limits) }
        assertThat(refused.capBytes).isEqualTo(100_000)
        assertThat(open(docx("<w:p/>".repeat(10_000)), limits).hasText).isFalse()
    }

    @Test
    fun `the text shown past its cap is too large, at its cap it is shown`() {
        val limits = DocumentLimits(textBytes = 1000)
        // 999 bytes and the paragraph's line feed: 1000, the cap — the bytes written, line feeds included.
        assertThat(text(docx(paragraphs("é".repeat(499) + "a")), limits)).hasLength(500)
        val refused = assertThrows<FileTooLargeException> { open(docx(paragraphs("é".repeat(500) + "a")), limits) }
        assertThat(refused.capBytes).isEqualTo(1000)
        // A count the file chooses, refused at once.
        assertThrows<FileTooLargeException> { open(odt("""<text:p>a<text:s text:c="2000000000"/>b</text:p>"""), limits) }
    }

    // ---------------------------------------------------------------------------------------------
    // Word: the 2.15.3's defects, and what it left out
    // ---------------------------------------------------------------------------------------------

    /** Word writes a text box twice, as a drawing and as its VML fallback: the 2.15.3 showed it twice. */
    @Test
    fun `a text box written twice shows once`() {
        val box = { text: String -> "<w:txbxContent>${paragraphs(text)}</w:txbxContent>" }
        val xml = "<w:p><w:r><w:t>Avant</w:t></w:r><w:r><mc:AlternateContent>" +
            "<mc:Choice Requires=\"wps\">${box("Boîte")}</mc:Choice>" +
            "<mc:Choice Requires=\"other\">${box("Autre choix")}</mc:Choice>" +
            "<mc:Fallback>${box("Repli")}</mc:Fallback>" +
            "</mc:AlternateContent></w:r><w:r><w:t>Après</w:t></w:r></w:p>"
        assertThat(text(docx(xml))).isEqualTo("Avant\nBoîte\nAprès")
        val fallbackOnly = "<w:p><w:r><mc:AlternateContent><mc:Fallback>${box("Seul")}</mc:Fallback></mc:AlternateContent></w:r></w:p>"
        assertThat(text(docx(fallbackOnly))).isEqualTo("Seul")
    }

    /** The 2.15.3 glued the outer paragraph's text to the box's first line. */
    @Test
    fun `a paragraph inside another ends its line, and the outer one goes on after it`() {
        val xml = "<w:p><w:r><w:t>Fin du document.</w:t></w:r><w:r><w:txbxContent>" +
            paragraphs("Zone 1", "Zone 2") + "</w:txbxContent></w:r><w:r><w:t>Suite</w:t></w:r></w:p>"
        assertThat(text(docx(xml))).isEqualTo("Fin du document.\nZone 1\nZone 2\nSuite")
    }

    /** `<w:tabs><w:tab …/>` is a tab stop of the paragraph's settings, not a tab: the 2.15.3's regex counted each one. */
    @Test
    fun `tab stops are not tabs, and run breaks and hyphens are not lost`() {
        val xml = """<w:p><w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/><w:tab w:val="right" w:pos="9000"/></w:tabs></w:pPr>""" +
            "<w:r><w:t>Nom</w:t><w:tab/><w:t>e</w:t><w:noBreakHyphen/><w:t>mail</w:t><w:cr/><w:t>fin</w:t><w:ptab/><w:t>x</w:t></w:r></w:p>"
        assertThat(text(docx(xml))).isEqualTo("Nom\te-mail\nfin\tx")
    }

    @Test
    fun `deleted text and field codes are not shown, a field's result is`() {
        val xml = "<w:p><w:del><w:r><w:delText>supprimé </w:delText></w:r></w:del><w:r><w:t>gardé</w:t></w:r>" +
            "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r>" +
            "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t> 3</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
        assertThat(text(docx(xml))).isEqualTo("gardé 3")
    }

    /** The 2.15.3's rules for Word: an empty paragraph is a blank line, never more than one in a row; the end trimmed. */
    @Test
    fun `blank lines collapse to one and the end is trimmed`() {
        val xml = paragraphs("A") + "<w:p/><w:p/><w:p/>" + paragraphs("B") + "<w:p/>" + paragraphs("C  ") + "<w:p/><w:p/>"
        assertThat(text(docx(xml))).isEqualTo("A\n\nB\n\nC")
        assertThat(text(docx("<w:p><w:r><w:t>a</w:t><w:br/><w:br/><w:br/><w:br/><w:t>b</w:t></w:r></w:p>"))).isEqualTo("a\n\nb")
        assertThat(text(docx(paragraphs("x&#13;&#10;y&#13;z")))).isEqualTo("x\ny\nz")
    }

    /** Matched by namespace, not by prefix: Word writes `w:`, the format does not require it; and strict OOXML. */
    @Test
    fun `any prefix of the Word namespace, transitional or strict`() {
        val other = """<x:document xmlns:x="$WORD_NS"><x:body><x:p><x:r><x:t>autre préfixe</x:t></x:r></x:p></x:body></x:document>"""
        assertThat(text(archive("x.docx", "word/document.xml" to other))).isEqualTo("autre préfixe")
        val strict = WORD_HEAD.replace(WORD_NS, "http://purl.oclc.org/ooxml/wordprocessingml/main") + paragraphs("strict") + WORD_TAIL
        assertThat(text(archive("strict.docx", "word/document.xml" to strict))).isEqualTo("strict")
    }

    @Test
    fun `a part in UTF-16 or with a UTF-8 BOM is read`() {
        val xml = WORD_HEAD + paragraphs("Été 😀") + WORD_TAIL
        val utf16 = xml.replace("UTF-8", "UTF-16")
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + utf16.toByteArray(Charsets.UTF_16BE)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml.toByteArray()
        listOf(le, be, bom).forEachIndexed { i, bytes ->
            assertThat(text(archiveOfBytes("enc$i.docx", "word/document.xml" to bytes))).isEqualTo("Été 😀")
        }
    }

    @Test
    fun `a document of pictures only has no text`() {
        val picture = "<w:p><w:r><w:drawing><wp:inline xmlns:wp=\"urn:x\"/></w:drawing></w:r></w:p>"
        val document = open(docx(picture + "<w:p><w:r><w:t>  </w:t></w:r></w:p>"))
        assertThat(document.hasText).isFalse()
        assertThat(open(odt("<text:p>  <text:s text:c=\"4\"/></text:p>")).hasText).isFalse()
    }

    // ---------------------------------------------------------------------------------------------
    // OpenDocument: the 2.15.3's defects, and what it left out
    // ---------------------------------------------------------------------------------------------

    /** `<text:s/>` is a space: erased, it glued words — Word's own export writes one at the edge of each run. */
    @Test
    fun `text s is one space, or as many as it counts`() {
        val xml = """<text:p>Rapport d'été<text:s/>—<text:s/>Année</text:p><text:p>a<text:s text:c="3"/>b</text:p>"""
        assertThat(text(odt(xml))).isEqualTo("Rapport d'été — Année\na   b")
    }

    /**
     * The 2.15.3 ended a paragraph at the first `</text:p>`, the note's: the rest of the outer paragraph was
     * lost, and the note glued to it. Notes are left out, as a Word document's (other parts).
     */
    @Test
    fun `a footnote is left out and the paragraph keeps its end`() {
        val xml = "<text:p>Avant la note<text:note text:note-class=\"footnote\"><text:note-citation>1</text:note-citation>" +
            "<text:note-body><text:p>Le texte de la note</text:p></text:note-body></text:note> et après.</text:p>"
        assertThat(text(odt(xml))).isEqualTo("Avant la note et après.")
    }

    @Test
    fun `comments and deleted text of tracked changes are not shown`() {
        val tracked = "<text:tracked-changes><text:changed-region text:id=\"c1\"><text:deletion>" +
            "<text:p>Texte supprimé</text:p></text:deletion></text:changed-region></text:tracked-changes>"
        val comment = "<office:annotation><dc:creator>Moi</dc:creator><text:p>Un commentaire</text:p></office:annotation>"
        assertThat(text(odt("$tracked<text:p>Le texte$comment gardé</text:p>"))).isEqualTo("Le texte gardé")
    }

    /** A frame anchored in a paragraph: its text box's paragraphs are lines of their own (the 2.15.3 glued the first). */
    @Test
    fun `a text box inside a paragraph is lines of its own`() {
        val xml = "<text:p>Fin du document.<draw:frame><draw:text-box>" +
            "<text:p>Texte de la zone</text:p></draw:text-box></draw:frame></text:p>"
        assertThat(text(odt(xml))).isEqualTo("Fin du document.\nTexte de la zone")
    }

    /** Only paragraphs and headings are text: a frame's title, as Word writes one, is not. */
    @Test
    fun `text outside paragraphs is not shown`() {
        val xml = "<draw:frame><svg:title>Titre du cadre</svg:title><draw:text-box><text:p>Dedans</text:p></draw:text-box></draw:frame>"
        assertThat(text(odt(xml))).isEqualTo("Dedans")
    }

    // ---------------------------------------------------------------------------------------------

    private fun open(file: File, limits: DocumentLimits = DocumentLimits()): DocumentText =
        runBlocking { DocumentText.read(StorageFileSource(file), limits) }

    private fun text(file: File, limits: DocumentLimits = DocumentLimits()): String = text(open(file, limits))

    private fun text(document: DocumentText): String = document.document.text(0, document.document.rowCount)

    private fun archive(name: String, vararg parts: Pair<String, String>): File =
        archiveOfBytes(name, *parts.map { (entry, content) -> entry to content.toByteArray() }.toTypedArray())

    private fun archiveOfBytes(name: String, vararg parts: Pair<String, ByteArray>): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            parts.forEach { (entry, content) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun docx(body: String, name: String = "d.docx"): File = archive(name, "word/document.xml" to WORD_HEAD + body + WORD_TAIL)

    private fun odt(body: String, name: String = "d.odt"): File = archive(name, "content.xml" to ODF_HEAD + body + ODF_TAIL)

    private fun paragraphs(vararg texts: String): String =
        texts.joinToString("") { """<w:p><w:r><w:t xml:space="preserve">$it</w:t></w:r></w:p>""" }

    /** [inner] inside [levels] content controls (`w:sdt`), each one level deeper, all closed. */
    private fun nested(levels: Int, inner: String): String = "<w:sdt>".repeat(levels) + inner + "</w:sdt>".repeat(levels)

    /** [declaration] where a DOCTYPE stands: after the XML declaration that opens [head]. */
    private fun withDoctype(head: String, declaration: String): String = head.replaceFirst("?>", "?>$declaration")

    private fun relationships(target: String): String =
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" """ +
            """Target="$target"/></Relationships>"""

    private fun assertWithin(millis: Long, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(millis)
    }

    private companion object {
        /** The 2.15.3's budget: a factor of a thousand between a linear scan and its quadratic regex. */
        const val BUDGET_MILLIS = 2_000L
        const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
        const val WORD_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="$WORD_NS" """ +
            """xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"><w:body>"""
        const val WORD_TAIL = "</w:body></w:document>"
        const val ODF_HEAD = """<?xml version="1.0" encoding="UTF-8"?><office:document-content xmlns:office="$OFFICE_NS" """ +
            """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
            """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
            """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/"><office:body><office:text>"""
        const val ODF_TAIL = "</office:text></office:body></office:document-content>"
        val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    }
}

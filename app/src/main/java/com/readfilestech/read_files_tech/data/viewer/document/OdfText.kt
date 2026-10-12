package com.readfilestech.read_files_tech.data.viewer.document

import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * The text of an OpenDocument text or presentation (.odt, .odp): its `content.xml`, read as the 2.15.3
 * read it — one line per paragraph or heading (`text:p`, `text:h`), in the document's order, blank ones
 * left out; `<text:line-break/>` a line feed, `<text:tab/>` a tab; a presentation's speaker notes
 * included, as the 2.15.3 showed them.
 *
 * What the 2.15.3 got wrong, fixed: `<text:s/>` — the spaces LibreOffice and Word write as an element —
 * was erased, gluing words (Word's export writes a space at the edge of a run that way, and splits its
 * runs around accented letters: "Rapport d'été — Année" showed "Rapport d'été—Année"); a paragraph
 * holding another one (a footnote, a comment, a text box) ended at the inner one's end, so the rest of
 * the outer paragraph was lost; deleted text kept for tracked changes (`text:tracked-changes`) showed
 * as if it were there.
 *
 * Not shown, coherent with a Word document's text: footnotes and endnotes (`text:note`, citation and
 * body), comments (`office:annotation`), headers and footers (in `styles.xml`), list bullets and numbers.
 */
internal object OdfText {
    const val CONTENT = "content.xml"
    private const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"

    suspend fun read(zip: ZipFile, part: ZipEntry, limits: DocumentLimits): DocumentText = zip.parsePart(part, limits) { xml ->
        xml.requireRoot(setOf(OFFICE), "document-content")
        val text = PlainTextBuilder(limits.textBytes, dropBlankLines = true)
        Body(xml, text).read()
        DocumentText(text.finish(), text.hasText)
    }

    private class Body(private val xml: XmlEvents, private val text: PlainTextBuilder) {
        suspend fun read() {
            while (true) {
                when (xml.next()) {
                    XmlPullParser.END_DOCUMENT -> return
                    XmlPullParser.START_TAG -> start()
                    XmlPullParser.END_TAG -> if (isParagraph()) text.endParagraph()
                    XmlPullParser.TEXT -> if (text.inParagraph) text.text(xml.text)
                }
            }
        }

        private fun isParagraph() = xml.namespace == TEXT && (xml.name == "p" || xml.name == "h")

        private suspend fun start() {
            when {
                isParagraph() -> text.startParagraph()
                xml.namespace == TEXT -> inText(xml.name)
                xml.namespace == OFFICE && xml.name == "annotation" -> xml.skip()
            }
        }

        private suspend fun inText(name: String) {
            when (name) {
                "note", "tracked-changes" -> xml.skip()
                "s" -> if (text.inParagraph) text.spaces(xml.attribute(TEXT, "c")?.toLongOrNull() ?: 1)
                "tab" -> if (text.inParagraph) text.text("\t")
                "line-break" -> if (text.inParagraph) text.text("\n")
            }
        }
    }
}

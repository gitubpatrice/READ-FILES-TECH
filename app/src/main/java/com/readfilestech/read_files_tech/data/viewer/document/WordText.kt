package com.readfilestech.read_files_tech.data.viewer.document

import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * The text of a Word document (.docx): its main part, `word/document.xml` where Word writes it — the
 * 2.15.3's only source, read the same way: one line per paragraph, table cells included (one paragraph,
 * one line), `<w:br/>` a line feed, `<w:tab/>` a tab, at most one blank line in a row.
 *
 * What the 2.15.3 got wrong, fixed: a text box — written twice by Word, as a drawing and as its VML
 * fallback — showed twice (`mc:Fallback` is skipped when a `mc:Choice` was read); every tab stop of a
 * paragraph's settings (`<w:tabs><w:tab …/>`) added a tab; `<w:cr/>` and `<w:noBreakHyphen/>` were lost,
 * gluing two words.
 *
 * Not shown, as in the 2.15.3: headers and footers (other parts, page furniture repeated on each page),
 * footnotes, endnotes and comments (other parts), deleted text (`w:delText`), field codes
 * (`w:instrText`), list bullets and numbers (computed from `numbering.xml` at layout).
 */
internal object WordText {
    private const val TRANSITIONAL = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val STRICT = "http://purl.oclc.org/ooxml/wordprocessingml/main"
    private val WORD = setOf(TRANSITIONAL, STRICT)
    private const val COMPATIBILITY = "http://schemas.openxmlformats.org/markup-compatibility/2006"

    private const val RELATIONSHIPS = "_rels/.rels"
    private const val WHERE_WORD_WRITES_IT = "word/document.xml"

    /** The end of the type of the package's relationship to its main part, transitional and strict. */
    private const val OFFICE_DOCUMENT = "/officeDocument"

    /**
     * The main part of a Word document: the one the package's relationships name, else where Word writes
     * it; null when the archive has neither. The relationships of an Excel or PowerPoint file name a part
     * whose root is not a Word document: [read] refuses it.
     */
    suspend fun mainPart(zip: ZipFile, limits: DocumentLimits): ZipEntry? {
        val named = zip.getEntry(RELATIONSHIPS)?.let { officeDocument(zip, it, limits) }?.let(zip::getEntry)
        return named ?: zip.getEntry(WHERE_WORD_WRITES_IT)
    }

    suspend fun read(zip: ZipFile, part: ZipEntry, limits: DocumentLimits): DocumentText = zip.parsePart(part, limits) { xml ->
        xml.requireRoot(WORD, "document")
        val text = PlainTextBuilder(limits.textBytes, dropBlankLines = false, maxNewlines = 2)
        Body(xml, text, limits.depth).read()
        DocumentText(text.finish(), text.hasText)
    }

    /** The target of the relationship to the main part, from the package's root: `word/document.xml`. */
    private suspend fun officeDocument(zip: ZipFile, rels: ZipEntry, limits: DocumentLimits): String? = zip.parsePart(rels, limits) { xml ->
        var target: String? = null
        var type = xml.next()
        while (target == null && type != XmlPullParser.END_DOCUMENT) {
            val main = type == XmlPullParser.START_TAG && xml.depth == 2 && xml.name == "Relationship" &&
                xml.attribute(null, "Type")?.endsWith(OFFICE_DOCUMENT) == true &&
                xml.attribute(null, "TargetMode") != "External"
            // Found without a target: empty, which ends the search and names nothing.
            if (main) target = xml.attribute(null, "Target")?.removePrefix("/").orEmpty() else type = xml.next()
        }
        target?.ifEmpty { null }
    }

    private class Body(private val xml: XmlEvents, private val text: PlainTextBuilder, depth: Int) {
        /** By depth: whether the element open there is a run (`w:r`), whose content a `w:tab` is. */
        private val run = BooleanArray(depth + 2)

        /** By depth: whether the `mc:AlternateContent` open there has had one of its choices read. */
        private val chosen = BooleanArray(depth + 2)
        private var inText = false

        suspend fun read() {
            while (true) {
                when (xml.next()) {
                    XmlPullParser.END_DOCUMENT -> return
                    XmlPullParser.START_TAG -> start()
                    XmlPullParser.END_TAG -> end()
                    XmlPullParser.TEXT -> if (inText) text.text(xml.text)
                }
            }
        }

        private suspend fun start() {
            val depth = xml.depth
            run[depth] = false
            when (xml.namespace) {
                in WORD -> word(xml.name, inRun = run[depth - 1], depth)
                COMPATIBILITY -> compatibility(xml.name, depth)
            }
        }

        private fun word(name: String, inRun: Boolean, depth: Int) {
            when (name) {
                "p" -> text.startParagraph()
                "r" -> run[depth] = true
                "t" -> inText = true
                "tab", "ptab" -> if (inRun) text.text("\t")
                "br", "cr" -> if (inRun) text.text("\n")
                "noBreakHyphen" -> if (inRun) text.text("-")
            }
        }

        /** One version of what `mc:AlternateContent` offers: its first choice, else its fallback. */
        private suspend fun compatibility(name: String, depth: Int) {
            when (name) {
                "AlternateContent" -> chosen[depth] = false
                "Choice", "Fallback" -> if (chosen[depth - 1]) xml.skip() else chosen[depth - 1] = true
            }
        }

        private fun end() {
            if (xml.namespace !in WORD) return
            when (xml.name) {
                "p" -> text.endParagraph()
                "t" -> inText = false
            }
        }
    }
}

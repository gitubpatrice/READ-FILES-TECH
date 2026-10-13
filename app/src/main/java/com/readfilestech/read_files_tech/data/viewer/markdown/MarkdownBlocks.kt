package com.readfilestech.read_files_tech.data.viewer.markdown

/** How a run of text is drawn: [BOLD], [ITALIC], [STRIKE] and [CODE] combined; [IMAGE] alone. */
object MarkdownStyle {
    const val BOLD = 1
    const val ITALIC = 2
    const val STRIKE = 4
    const val CODE = 8

    /** The image's place, one [IMAGE_MARK] character: drawn as an icon, never loaded. */
    const val IMAGE = 16

    const val IMAGE_MARK = '\uFFFC'
}

/** The characters from [start] until [end] of a text, drawn in [style], and the link they lead to. */
data class MarkdownRun(val start: Int, val end: Int, val style: Int, val link: String?)

/** One item of the rendered view, laid out when it is shown. */
sealed interface MarkdownBlock {
    /** Block quotes around it. */
    val quotes: Int

    /** Lists around it. */
    val lists: Int
}

/**
 * A paragraph, or a heading of [heading] 1 to 6 — or a piece of one: a long paragraph is cut into pieces
 * of [com.readfilestech.read_files_tech.data.viewer.text.TextDocument.ROW_BYTES] characters, [continued]
 * after the first, so that no text laid out is longer. [marker] is a list item's ("•", "3.").
 */
data class MarkdownText(
    val text: String,
    val runs: List<MarkdownRun>,
    val heading: Int = 0,
    val marker: String? = null,
    val continued: Boolean = false,
    override val quotes: Int = 0,
    override val lists: Int = 0,
) : MarkdownBlock

/** Lines of a code block (or of raw HTML, shown as written), [continued] after its first piece. */
data class MarkdownCode(
    val text: String,
    val continued: Boolean = false,
    override val quotes: Int = 0,
    override val lists: Int = 0,
) : MarkdownBlock

data class MarkdownRule(override val quotes: Int = 0, override val lists: Int = 0) : MarkdownBlock

/** A cell of a table, aligned [alignment] (null: the start). */
data class MarkdownCell(val text: String, val runs: List<MarkdownRun>, val alignment: MarkdownAlignment? = null)

enum class MarkdownAlignment { LEFT, CENTER, RIGHT }

/** A row of the table numbered [table] in the document; the rows of one table have as many cells. */
data class MarkdownTableRow(
    val table: Int,
    val cells: List<MarkdownCell>,
    val header: Boolean,
    override val quotes: Int = 0,
    override val lists: Int = 0,
) : MarkdownBlock

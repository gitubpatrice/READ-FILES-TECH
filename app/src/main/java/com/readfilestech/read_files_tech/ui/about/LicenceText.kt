package com.readfilestech.read_files_tech.ui.about

/**
 * A licence text — NOTICE, LICENSE — laid out for a phone. Those files are wrapped at about 80 columns; shown as
 * written on a 400 dp screen, each of their lines wrapped again, a sentence came out in ragged halves (seen on the
 * emulator). A line the file wrapped is joined to the next one; a line its author ended — a short one, one ending a
 * sentence before a new one, a list item — stays a line of its own.
 */
internal object LicenceText {
    /** A paragraph made only of a rule (`----`): drawn as a divider, not as text. */
    const val RULE = "─"

    /** A line at least this long was wrapped by the file, not ended by its author. */
    private const val WRAPPED = 60

    /** How much deeper a continuation may be indented: the hanging indent of a list item, of a heading's second line. */
    private const val HANGING = 8
    private const val MIN_RULE = 10

    private val BLANK_LINE = Regex("\n[ \t]*\n")
    private val LIST_ITEM = Regex("""^(?:[-*•]|\d+[.)]|\([a-z0-9]+\))\s""")
    private const val SENTENCE_ENDS = ".:;!?"

    /** [text] in paragraphs, split on its blank lines, each one's wrapped lines joined. */
    fun paragraphs(text: String): List<String> = text.replace("\r\n", "\n").split(BLANK_LINE)
        .map { it.trim('\n') }
        .filter(String::isNotBlank)
        .flatMap(::split)

    /** One paragraph: its rules apart, its other lines joined where the file wrapped them. */
    private fun split(paragraph: String): List<String> {
        val result = ArrayList<String>()
        val lines = ArrayList<String>()
        var last: String? = null
        fun flush() {
            if (lines.isNotEmpty()) result += lines.joinToString("\n")
            lines.clear()
        }
        for (line in paragraph.lines().map(String::trimEnd)) {
            val previous = last
            when {
                isRule(line) -> {
                    flush()
                    result += RULE
                }
                previous != null && lines.isNotEmpty() && continues(previous, line) ->
                    lines[lines.lastIndex] = lines.last() + " " + line.trim()
                else -> lines += line
            }
            last = line
        }
        flush()
        return result
    }

    /**
     * Whether [line] goes on with [previous], the line before it in the file: that one reached the file's margin,
     * and this one starts nothing new — no list item, no new sentence after a finished one, no block set apart.
     */
    private fun continues(previous: String, line: String): Boolean {
        val content = line.trim()
        if (previous.length < WRAPPED || content.isEmpty()) return false
        val newSentence = previous.last() in SENTENCE_ENDS && content.first().isUpperCase()
        return !newSentence && !LIST_ITEM.containsMatchIn(content) && indent(line) <= indent(previous) + HANGING
    }

    private fun indent(line: String): Int = line.length - line.trimStart().length

    private fun isRule(line: String): Boolean = line.length >= MIN_RULE && line.all { it == '-' }
}

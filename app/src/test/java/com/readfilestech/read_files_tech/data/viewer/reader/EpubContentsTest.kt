package com.readfilestech.read_files_tech.data.viewer.reader

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.StringReader

/** A table of contents' labels, as [EpubContents] keeps them: at most [EpubContents.MAX_LABEL] characters. */
class EpubContentsTest {
    private fun nav(body: String): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        val tokens = HtmlTokenizer(StringReader("<nav epub:type=\"toc\">$body</nav>"), EpubContents.ATTRIBUTES)
        runBlocking { EpubContents { href, title -> found += href to title }.readNav(tokens) }
        return found
    }

    @Test
    fun `a label cut at its cap never keeps half of a surrogate pair`() {
        // Claude review R13: the cut fell between the two halves of a character outside the BMP.
        val emoji = String(Character.toChars(0x1F600))
        val found = nav("<a href=\"c1.xhtml\">${"a".repeat(EpubContents.MAX_LABEL - 1)}$emoji</a>")
        assertThat(found).containsExactly("c1.xhtml" to "a".repeat(EpubContents.MAX_LABEL - 1))
    }

    @Test
    fun `a label up to its cap is kept whole, a character outside the BMP included`() {
        val emoji = String(Character.toChars(0x1F600))
        val label = "a".repeat(EpubContents.MAX_LABEL - 2) + emoji
        assertThat(nav("<a href=\"c1.xhtml\">$label</a>")).containsExactly("c1.xhtml" to label)
    }
}

package com.readfilestech.read_files_tech.ui.tools.editor

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** What the editor's text field does to a CR typed or pasted: the rule, apart from Compose. */
class LineBreakInputTest {
    /** The whole [text] through the rule, as if all of it had just been pasted. */
    private fun pasted(text: String): String {
        val rewrites = LineBreakInput.places(listOf(intArrayOf(0, text.length)), text.length)
            .mapNotNull { (from, to) -> LineBreakInput.rewrite(text, from, to) }
        val out = StringBuilder(text)
        rewrites.asReversed().forEach { out.replace(it.start, it.end, it.text) }
        return out.toString()
    }

    @Test
    fun `a CRLF is one line break, a CR alone another, as when a file is opened`() {
        assertThat(pasted("a\r\nb\rc\nd")).isEqualTo("a\nb\nc\nd")
        assertThat(pasted("a\r\r\nb")).isEqualTo("a\n\nb")
        assertThat(pasted("\r")).isEqualTo("\n")
        assertThat(pasted("no break")).isEqualTo("no break")
    }

    @Test
    fun `only the span from the first CR to the last is rewritten`() {
        val rewrite = LineBreakInput.rewrite("ab\r\ncd\ref", 0, 9)!!
        assertThat(rewrite.start).isEqualTo(2)
        assertThat(rewrite.end).isEqualTo(7)
        assertThat(rewrite.text).isEqualTo("\ncd\n")
        assertThat(LineBreakInput.rewrite("abc", 0, 3)).isNull()
    }

    @Test
    fun `a CR typed before a LF already there is read with it`() {
        // "a\n" in the field, a CR typed at 1: the change is [1, 2), the LF after it outside.
        val text = "a\r\n"
        val places = LineBreakInput.places(listOf(intArrayOf(1, 2)), text.length)
        val rewrite = LineBreakInput.rewrite(text, places.single()[0], places.single()[1])!!
        assertThat(text.replaceRange(rewrite.start, rewrite.end, rewrite.text)).isEqualTo("a\n")
    }

    @Test
    fun `changes that touch once widened are one place`() {
        val places = LineBreakInput.places(listOf(intArrayOf(5, 6), intArrayOf(1, 2), intArrayOf(3, 4)), 10)
        assertThat(places.map { it.toList() }).containsExactly(listOf(0, 7)).inOrder()
        val apart = LineBreakInput.places(listOf(intArrayOf(1, 2), intArrayOf(6, 7)), 10)
        assertThat(apart.map { it.toList() }).containsExactly(listOf(0, 3), listOf(5, 8)).inOrder()
    }

    /** Claude review R23: a cursor inside a rewritten span went to its end. */
    @Test
    fun `a position keeps its place among the characters kept`() {
        // `x\r\nf(|)\r\ny`: the cursor between the parentheses, at 5.
        val text = "x\r\nf()\r\ny"
        val rewrites = listOfNotNull(LineBreakInput.rewrite(text, 0, text.length))
        val after = text.replaceRange(rewrites.single().start, rewrites.single().end, rewrites.single().text)
        assertThat(after).isEqualTo("x\nf()\ny")
        val moved = LineBreakInput.moved(5, text, rewrites)
        assertThat(after.substring(0, moved)).isEqualTo("x\nf(")
        // Before the span and after it: moved by the CR dropped before them only.
        assertThat(LineBreakInput.moved(0, text, rewrites)).isEqualTo(0)
        assertThat(LineBreakInput.moved(text.length, text, rewrites)).isEqualTo(after.length)
        // A CR made a LF moves nothing.
        assertThat(LineBreakInput.moved(3, "a\rb\rc", listOfNotNull(LineBreakInput.rewrite("a\rb\rc", 0, 5)))).isEqualTo(3)
    }

    /** Claude review R22: one replace per CR — a paste of many CRLF made as many changes, each against the others. */
    @Test
    fun `a paste of many CRLF is rewritten in one place`() {
        val text = "x\r\n".repeat(50_000)
        val places = LineBreakInput.places(listOf(intArrayOf(0, text.length)), text.length)
        assertThat(places).hasSize(1)
        assertThat(pasted(text)).isEqualTo("x\n".repeat(50_000))
    }
}

package com.readfilestech.read_files_tech.data.tools

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.readfilestech.read_files_tech.data.viewer.text.TextDocument
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

/**
 * The comparison. Its oracles are not the code: Python's difflib and a longest common subsequence by
 * dynamic programming on pairs of texts (`diff-oracle.json`), and, on random sequences, the same dynamic
 * programming written here — a shortest edit script removes exactly the lines outside a longest common
 * subsequence — and the script applied to the first sequence giving the second.
 */
class LineDiffTest {
    private fun lines(text: String): TextLines = lines(text.encodeToByteArray())

    private fun lines(bytes: ByteArray): TextLines = runBlocking { TextLines.of(bytes, bytes.size) }

    private fun compare(a: String, b: String, lineEndings: Boolean = false, maxRows: Int = Int.MAX_VALUE) =
        runBlocking { LineDiff.compare(lines(a), lines(b), lineEndings, maxRows) }

    @Test
    fun `the lines difflib removes and adds, and no more than a longest common subsequence leaves`() {
        val pairs = JSONObject(resource("/tools/diff-oracle.json")).getJSONArray("pairs").objects()
        for (pair in pairs) {
            val name = pair.getString("name")
            val comparison = compare(pair.getString("a"), pair.getString("b"))
            val a = lines(pair.getString("a"))
            val b = lines(pair.getString("b"))
            val lcs = pair.getInt("lcs")
            assertWithMessage(name).that(comparison.removed).isEqualTo(a.count - lcs)
            assertWithMessage(name).that(comparison.added).isEqualTo(b.count - lcs)
            assertThat(comparison.approximate).isFalse()
            if (!pair.getBoolean("ambiguous")) {
                val removed = comparison.rows.filter { it.kind == DiffKind.REMOVED }.map { it.text }.sorted()
                val added = comparison.rows.filter { it.kind == DiffKind.ADDED }.map { it.text }.sorted()
                assertWithMessage(name).that(removed).isEqualTo(pair.getJSONArray("removed").strings())
                assertWithMessage(name).that(added).isEqualTo(pair.getJSONArray("added").strings())
            }
        }
    }

    @Test
    fun `on random sequences, a shortest script, which turns the first into the second`() {
        val random = Random(20_261_014)
        repeat(RANDOM_CASES) {
            val alphabet = random.nextInt(1, 6)
            val a = IntArray(random.nextInt(0, 40)) { random.nextInt(alphabet) }
            val b = IntArray(random.nextInt(0, 40)) { random.nextInt(alphabet) }
            val script = runBlocking { LineDiff.script(a, b) }
            assertApplies(script, a, b)
            val lcs = lcs(a, b)
            assertThat(script.count(LineDiff.REMOVED)).isEqualTo(a.size - lcs)
            assertThat(script.count(LineDiff.ADDED)).isEqualTo(b.size - lcs)
        }
    }

    @Test
    fun `past its budget the search stops, says it, and its script still turns one into the other`() {
        val a = IntArray(400) { it }
        val b = IntArray(400) { if (it % 3 == 0) it else it + 1000 }
        val myers = runBlocking { LineDiff.compare(lines(a.joinToString("\n")), lines(b.joinToString("\n")), false, work = 50) }
        assertThat(myers.approximate).isTrue()
        val script = runBlocking { LineDiff.script(a, b, work = 50) }
        assertApplies(script, a, b)
        assertThat(script.count(LineDiff.REMOVED)).isAtLeast(a.size - lcs(a, b))
    }

    @Test
    fun `two large files with a few changes are compared whole and at once`() {
        val size = 300_000
        val a = (0 until size).joinToString("\n") { "line $it" }
        val b = (0 until size).joinToString("\n") { if (it % 100_000 == 5) "changed $it" else "line $it" }
        val comparison = compare(a, b, maxRows = 500)
        assertThat(comparison.removed).isEqualTo(3)
        assertThat(comparison.added).isEqualTo(3)
        assertThat(comparison.approximate).isFalse()
    }

    @Test
    fun `CRLF and LF are the same lines unless line endings count`() {
        val crlf = "a\r\nb\r\nc\r\n"
        val lf = "a\nb\nc"
        assertThat(compare(crlf, lf).sameLines).isTrue()
        val counted = compare(crlf, lf, lineEndings = true)
        assertThat(counted.removed).isEqualTo(3)
        assertThat(counted.rows.first().text).isEqualTo("a" + Char(0x240D))
    }

    @Test
    fun `the same text in UTF-16 and in UTF-8 has the same lines`() {
        val text = "première ligne\nsecond 😀\n"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val comparison = runBlocking { LineDiff.compare(lines(utf16), lines(text), false) }
        assertThat(comparison.sameLines).isTrue()
    }

    @Test
    fun `a last line without a LF counts, a final LF opens none, an empty file has none`() {
        assertThat(lines("").count).isEqualTo(0)
        assertThat(lines("\n").count).isEqualTo(1)
        assertThat(lines("a\nb").count).isEqualTo(2)
        assertThat(lines("a\nb\n").count).isEqualTo(2)
        assertThat(compare("", "").sameLines).isTrue()
    }

    @Test
    fun `a file of too many lines is refused`() {
        val text = "x\n".repeat(11)
        assertThrows<TooManyLinesException> {
            runBlocking { TextLines.of(text.encodeToByteArray(), text.length, maxLines = 10) }
        }
        assertThat(runBlocking { TextLines.of(text.encodeToByteArray(), text.length, maxLines = 11) }.count).isEqualTo(11)
    }

    @Test
    fun `a change is shown with three lines around it, the rest folded`() {
        val a = (1..100).joinToString("\n") { "line $it" }
        val b = a.replace("line 50\n", "line fifty\n")
        val rows = compare(a, b).rows
        assertThat(rows.map { it.kind }).containsExactly(
            DiffKind.SKIPPED, DiffKind.SAME, DiffKind.SAME, DiffKind.SAME, DiffKind.REMOVED, DiffKind.ADDED,
            DiffKind.SAME, DiffKind.SAME, DiffKind.SAME, DiffKind.SKIPPED,
        ).inOrder()
        assertThat(rows.first().skipped).isEqualTo(46)
        assertThat(rows[4]).isEqualTo(DiffRow(DiffKind.REMOVED, 50, 0, "line 50"))
        assertThat(rows[5]).isEqualTo(DiffRow(DiffKind.ADDED, 0, 50, "line fifty"))
        assertThat(rows.last().skipped).isEqualTo(47)
    }

    @Test
    fun `the rows stop at their bound, the counts do not`() {
        val a = (1..2000).joinToString("\n") { "a $it" }
        val b = (1..2000).joinToString("\n") { "b $it" }
        val comparison = compare(a, b, maxRows = 500)
        assertThat(comparison.rows).hasSize(500)
        assertThat(comparison.truncated).isTrue()
        assertThat(comparison.removed).isEqualTo(2000)
        assertThat(comparison.added).isEqualTo(2000)
    }

    @Test
    fun `a binary file, or one long line, is compared without harm, its rows cut`() {
        val binary = ByteArray(100_000) { (it * 131 + 17).toByte() }
        val comparison = runBlocking { LineDiff.compare(lines(binary), lines("text\n"), false) }
        assertThat(comparison.added).isEqualTo(1)
        val long = "é".repeat(10_000)
        val row = compare(long, "other").rows.first { it.kind == DiffKind.REMOVED }
        assertThat(row.text).endsWith("…")
        assertThat(row.text.encodeToByteArray().size).isAtMost(TextDocument.ROW_BYTES + 3)
        assertThat(row.text.dropLast(1).all { it == 'é' }).isTrue()
    }

    @Test
    fun `identical bytes are told apart from the same lines`() {
        val a = "x\r\ny".encodeToByteArray()
        val b = "x\ny".encodeToByteArray()
        assertThat(LineDiff.sameBytes(a, a.size, a.copyOf(), a.size)).isTrue()
        assertThat(LineDiff.sameBytes(a, a.size, b, b.size)).isFalse()
        assertThat(LineDiff.sameBytes(a, 1, b, 1)).isTrue()
    }

    /** The runs of [script], applied to [a], give [b]: each kept line is the same in both. */
    private fun assertApplies(script: Script, a: IntArray, b: IntArray) {
        var i = 0
        var j = 0
        for (run in 0 until script.size) {
            val length = script.length(run)
            when (script.op(run)) {
                LineDiff.SAME -> repeat(length) {
                    assertThat(a[i++]).isEqualTo(b[j++])
                }
                LineDiff.REMOVED -> i += length
                else -> j += length
            }
        }
        assertThat(i).isEqualTo(a.size)
        assertThat(j).isEqualTo(b.size)
    }

    private fun lcs(a: IntArray, b: IntArray): Int {
        var previous = IntArray(b.size + 1)
        for (x in a) {
            val current = IntArray(b.size + 1)
            for (j in b.indices) current[j + 1] = if (x == b[j]) previous[j] + 1 else maxOf(previous[j + 1], current[j])
            previous = current
        }
        return previous[b.size]
    }

    private companion object {
        const val RANDOM_CASES = 400
    }
}

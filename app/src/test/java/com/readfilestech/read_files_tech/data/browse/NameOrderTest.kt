package com.readfilestech.read_files_tech.data.browse

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * The order of names the user reads (docs D-033). The 2.15.x and the port before the five languages sorted the
 * lower-cased code points: "École" after "Zèbre", "Äpfel" after "Zug".
 */
class NameOrderTest {
    private val french = NameOrder(Locale.FRANCE)

    @Test
    fun `accented names take their place in the alphabet`() {
        assertThat(french.sort(listOf("Zèbre", "École", "abricot", "Écran", "Ecole"), { it }))
            .containsExactly("abricot", "Ecole", "École", "Écran", "Zèbre").inOrder()
        assertThat(NameOrder(Locale.GERMANY).sort(listOf("Zug", "Äpfel", "apfel", "Bär"), { it }))
            .containsExactly("apfel", "Äpfel", "Bär", "Zug").inOrder()
    }

    @Test
    fun `case only settles names otherwise the same, and the order is the same whatever the input order`() {
        val names = listOf("b", "A", "a", "B")
        val once = french.sort(names, { it })
        assertThat(once).containsExactly("a", "A", "b", "B").inOrder()
        assertThat(french.sort(names.reversed(), { it })).isEqualTo(once)
    }

    @Test
    fun `names the language holds equal still get one order`() {
        // "é" written as one character and as "e" plus its accent: equal for the collator, not for a sort.
        val composed = Normalizer.normalize("été", Normalizer.Form.NFC)
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
        assertThat(decomposed).isNotEqualTo(composed)
        // The premise: without it this test would prove nothing.
        assertThat(Collator.getInstance(Locale.FRANCE).compare(composed, decomposed)).isEqualTo(0)
        val sorted = french.sort(listOf(composed, decomposed), { it })
        assertThat(french.sort(listOf(decomposed, composed), { it })).isEqualTo(sorted)
    }

    @Test
    fun `paths keep a folder's entries together, the folder before its entries`() {
        val paths = listOf("é/z", "e/y", "é/x", "é/", "e/", "a.txt")
        assertThat(french.sortPaths(paths, { it }))
            .containsExactly("a.txt", "e/", "e/y", "é/", "é/x", "é/z").inOrder()
        // A folder named without its final "/": still before what it holds.
        assertThat(french.sortPaths(listOf("docs/b", "docs", "docs/a"), { it }))
            .containsExactly("docs", "docs/a", "docs/b").inOrder()
    }

    @Test
    fun `two folders the language holds equal do not mix their entries`() {
        // A ZIP made on a Mac (NFD) and on Windows (NFC) can hold both "Été" folders.
        val nfc = Normalizer.normalize("Été", Normalizer.Form.NFC)
        val nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD)
        val paths = listOf("$nfc/z", "$nfd/y", "$nfc/a", "$nfd/b", "$nfc/m")
        val folders = french.sortPaths(paths, { it }).map { it.substringBefore('/') }
        assertThat(folders.distinct()).hasSize(2)
        assertThat(folders.zipWithNext().count { (a, b) -> a != b }).isEqualTo(1)
    }

    @Test
    fun `the explorer sorts by it, folders first`() {
        fun entry(name: String, folder: Boolean = false) =
            FileEntry(path = "/x/$name", name = name, isDirectory = folder, size = 0, modifiedMs = 0, isSymlink = false)
        val entries = listOf(entry("Zèbre.pdf"), entry("École", folder = true), entry("école.pdf"), entry("Archives", folder = true))
        val sorted = EntryOrder.sort(entries, SortMode.NAME, french)
        assertThat(sorted.map(FileEntry::name)).containsExactly("Archives", "École", "école.pdf", "Zèbre.pdf").inOrder()
    }
}

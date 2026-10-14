package com.readfilestech.read_files_tech.data.browse

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.search.InvalidPatternException
import com.readfilestech.read_files_tech.data.search.PatternProblem
import com.readfilestech.read_files_tech.data.search.SearchPattern
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** The bulk rename: the names it makes, those it refuses before renaming anything, and the batch itself. */
class BulkRenameTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var dir: File

    @BeforeEach
    fun setUp() {
        dir = File(tmp, "dir").apply { mkdirs() }
    }

    private fun entry(name: String, isDirectory: Boolean = false) =
        FileEntry(File(dir, name).path, name, isDirectory, size = 0, modifiedMs = 0, isSymlink = false)

    private fun preview(
        names: List<String>,
        rule: RenameRule,
        keepExtension: Boolean = true,
        existing: Set<String> = names.toSet(),
    ): List<RenameProposal> = BulkRename.preview(names.map { entry(it) }, rule, keepExtension, { existing })

    private fun newNames(names: List<String>, rule: RenameRule, keepExtension: Boolean = true) =
        preview(names, rule, keepExtension).map(RenameProposal::newName)

    private fun regex(text: String) = SearchPattern.regex(text, matchCase = true)

    @Test
    fun `numbering in the order given, padded, the extension kept or dropped`() {
        val names = listOf("b.jpg", "a.JPG", "c")
        assertThat(
            newNames(names, RenameRule.Numbering("Photo", start = 9, digits = 3))
        ).containsExactly("Photo_009.jpg", "Photo_010.JPG", "Photo_011").inOrder()
        assertThat(newNames(names, RenameRule.Numbering("Photo", start = 1, digits = 0), keepExtension = false))
            .containsExactly("Photo_1", "Photo_2", "Photo_3").inOrder()
    }

    @Test
    fun `a prefix and a suffix go around the name, before its last extension`() {
        assertThat(newNames(listOf("archive.tar.gz", "notes."), RenameRule.Affixes("old-", "-v2")))
            .containsExactly("old-archive.tar-v2.gz", "old-notes.-v2").inOrder()
    }

    /** The 2.15.3 took the dot of `.bashrc` for an extension, and a folder's dot too. */
    @Test
    fun `a hidden file and a folder have no extension`() {
        val entries = listOf(entry(".bashrc"), entry("v1.2", isDirectory = true))
        val names = BulkRename.preview(entries, RenameRule.Affixes("", "_old"), keepExtension = true, {
            emptySet()
        }).map(RenameProposal::newName)
        assertThat(names).containsExactly(".bashrc_old", "v1.2_old").inOrder()
    }

    /** F3: the 2.15.3's help said "Photo_$1", and the replacement was written as typed, `$1` included. */
    @Test
    fun `a regex replacement takes its groups, on the name without its extension`() {
        assertThat(newNames(listOf("IMG_0042.jpg", "IMG_7.JPG", "other.png"), RenameRule.Replace(regex("""IMG_(\d+)"""), "Photo_$1")))
            .containsExactly("Photo_0042.jpg", "Photo_7.JPG", "other.png").inOrder()
    }

    @Test
    fun `no pattern changes no name, extension included`() {
        assertThat(newNames(listOf("a.txt"), RenameRule.Replace(null, "x"), keepExtension = false)).containsExactly("a.txt")
        assertThat(preview(listOf("a.txt"), RenameRule.Replace(null, "x")).single().changes).isFalse()
    }

    @Test
    fun `a replacement naming a missing group is refused`() {
        val refused = assertThrows<InvalidPatternException> {
            newNames(listOf("IMG_1.jpg"), RenameRule.Replace(regex("IMG_(1)"), "$2"))
        }
        assertThat(refused.problem).isEqualTo(PatternProblem.BAD_REPLACEMENT)
    }

    @Test
    fun `invalid and too long names are refused`() {
        val invalid = preview(listOf("a.txt", "b.txt"), RenameRule.Replace(regex("^[ab]$"), "x/y"))
        assertThat(invalid.map(RenameProposal::problem)).containsExactly(NameProblem.INVALID, NameProblem.INVALID)
        assertThat(
            preview(listOf("a.txt"), RenameRule.Replace(regex("a"), ""), keepExtension = false).single().problem
        ).isEqualTo(NameProblem.INVALID)
        val long = preview(listOf("a.txt"), RenameRule.Affixes("é".repeat(126), ""))
        assertThat(long.single().problem).isEqualTo(NameProblem.TOO_LONG)
        assertThat(preview(listOf("a.txt"), RenameRule.Affixes("é".repeat(125), "")).single().problem).isNull()
    }

    /** Android's storage ignores case: two names that differ by case only are one name. */
    @Test
    fun `two new names equal but for case are refused, both`() {
        val proposals = preview(listOf("a.txt", "b.TXT"), RenameRule.Replace(regex("^[ab]$"), "same"))
        assertThat(proposals.map(RenameProposal::newName)).containsExactly("same.txt", "same.TXT").inOrder()
        assertThat(proposals.map(RenameProposal::problem)).containsExactly(NameProblem.DUPLICATE, NameProblem.DUPLICATE)
    }

    @Test
    fun `a name another item of the folder has is refused, case ignored, a batch item included`() {
        val existing = setOf("a.txt", "b.txt", "Taken.txt", ".hidden")
        val taken = preview(listOf("a.txt"), RenameRule.Replace(regex("a"), "taken"), existing = existing)
        assertThat(taken.single().problem).isEqualTo(NameProblem.TAKEN)
        // The second takes the first's name, which the first leaves in the same batch: refused, as the order
        // of the renames would decide.
        val chain = preview(listOf("Photo_2.jpg", "x.jpg"), RenameRule.Numbering("Photo", start = 1, digits = 0))
        assertThat(chain.map(RenameProposal::newName)).containsExactly("Photo_1.jpg", "Photo_2.jpg").inOrder()
        assertThat(chain.map(RenameProposal::problem)).containsExactly(null, NameProblem.TAKEN).inOrder()
        assertThat(
            preview(listOf("a.txt"), RenameRule.Replace(regex("a"), ".hidden"), keepExtension = false, existing = existing).single().problem
        )
            .isEqualTo(NameProblem.TAKEN)
    }

    @Test
    fun `its own name under another case is its own`() {
        val proposal = preview(listOf("photo.JPG"), RenameRule.Replace(regex("photo"), "photo"), keepExtension = false).single()
        assertThat(proposal.newName).isEqualTo("photo")
        val upper = preview(listOf("photo.jpg"), RenameRule.Replace(regex("photo"), "PHOTO")).single()
        assertThat(upper.problem).isNull()
        assertThat(upper.changes).isTrue()
    }

    @Test
    fun `the check runs for each name, and stops the preview`() {
        var checks = 0
        assertThrows<IllegalStateException> {
            BulkRename.preview((1..10).map { entry("f$it") }, RenameRule.Affixes("x", ""), true, { emptySet() }) {
                if (++checks == 3) error("stop")
            }
        }
        assertThat(checks).isEqualTo(3)
    }

    @Test
    fun `the batch is renamed by the explorer's own rename, what changes only`() {
        listOf("IMG_1.jpg", "IMG_2.jpg", "keep.txt").forEach { File(dir, it).writeText(it) }
        val proposals = BulkRename.preview(
            listOf("IMG_1.jpg", "IMG_2.jpg", "keep.txt").map { entry(it) },
            RenameRule.Replace(regex("""IMG_(\d)"""), "Photo_$1"),
            keepExtension = true,
            { folder -> folder.list().orEmpty().toSet() },
        )
        val report = BulkRename.apply(proposals, FileActions()::rename)
        assertThat(report).isEqualTo(RenameReport(2, emptyList()))
        assertThat(dir.list()!!.toList()).containsExactly("Photo_1.jpg", "Photo_2.jpg", "keep.txt")
        assertThat(File(dir, "Photo_2.jpg").readText()).isEqualTo("IMG_2.jpg")
    }

    @Test
    fun `a failure in the middle does not stop the batch, and is reported`() {
        val proposals = preview(listOf("a.txt", "b.txt", "c.txt"), RenameRule.Affixes("new-", ""))
        val failure = IOException("refused")
        val report = BulkRename.apply(proposals) { file, name -> if (file.name == "b.txt") throw failure else File(dir, name) }
        assertThat(report.renamed).isEqualTo(2)
        assertThat(report.failures).containsExactly(File(dir, "b.txt") to failure)
    }

    @Test
    fun `a batch holding a refused name is never applied`() {
        val proposals = preview(listOf("a.txt", "b.txt"), RenameRule.Replace(regex("^[ab]$"), "same"))
        var renamed = 0
        assertThrows<IllegalArgumentException> {
            BulkRename.apply(proposals) { file, _ ->
                renamed++
                file
            }
        }
        assertThat(renamed).isEqualTo(0)
    }
}

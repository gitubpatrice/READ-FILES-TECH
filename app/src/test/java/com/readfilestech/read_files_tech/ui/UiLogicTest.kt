package com.readfilestech.read_files_tech.ui

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.BatchResult
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.browse.Listing
import com.readfilestech.read_files_tech.data.browse.SortMode
import com.readfilestech.read_files_tech.ui.common.BatchMessages
import com.readfilestech.read_files_tech.ui.common.ByteSizes
import com.readfilestech.read_files_tech.ui.common.ByteUnits
import com.readfilestech.read_files_tech.ui.common.IoErrors
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.explorer.ExplorerUiState
import com.readfilestech.read_files_tech.ui.explorer.FolderProblem
import com.readfilestech.read_files_tech.ui.explorer.allSelected
import com.readfilestech.read_files_tech.ui.explorer.listed
import com.readfilestech.read_files_tech.ui.explorer.sortedBy
import com.readfilestech.read_files_tech.ui.explorer.toggled
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.navigation.Shortcuts
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/** The screens' logic that needs no Android: sizes, explorer state, batch messages, tile shortcuts. */
class UiLogicTest {
    private val en = ByteUnits("B", "KB", "MB", "GB")
    private val fr = ByteUnits("o", "Ko", "Mo", "Go")

    private fun entry(name: String, dir: Boolean = false) = FileEntry("/d/$name", name, dir, 0, 0, isSymlink = false)

    @Test
    fun `sizes in binary units, in the language of the screen`() {
        assertThat(ByteSizes.format(0, en, Locale.ENGLISH)).isEqualTo("0 B")
        assertThat(ByteSizes.format(1023, en, Locale.ENGLISH)).isEqualTo("1023 B")
        assertThat(ByteSizes.format(1536, en, Locale.ENGLISH)).isEqualTo("1.5 KB")
        // The 2.15.x wrote "1.5 KB" on a French screen.
        assertThat(ByteSizes.format(1536, fr, Locale.FRENCH)).isEqualTo("1,5 Ko")
        assertThat(ByteSizes.format(5L * 1024 * 1024 + 1, fr, Locale.FRENCH)).isEqualTo("5 Mo")
        assertThat(ByteSizes.format(3L * 1024 * 1024 * 1024 / 2, en, Locale.ENGLISH)).isEqualTo("1.5 GB")
    }

    @Test
    fun `no access under shared storage shows the banner over an incomplete listing, and the problem over an empty one`() {
        val dir = File("/storage/emulated/0")
        val state = ExplorerUiState()
        val partial = state.listed(
            dir,
            Listing.Listed(dir, listOf(entry("DCIM", dir = true))),
            denied = true,
            mounted = emptyList(),
            canGoBack = false
        )
        assertThat(partial.accessBanner).isTrue()
        assertThat(partial.problem).isNull()
        val empty = state.listed(dir, Listing.Listed(dir, emptyList()), denied = true, mounted = emptyList(), canGoBack = false)
        assertThat(empty.problem).isEqualTo(FolderProblem.ACCESS_DENIED)
        val unreadable = state.listed(dir, Listing.Unreadable, denied = false, mounted = emptyList(), canGoBack = false)
        assertThat(unreadable.problem).isEqualTo(FolderProblem.UNREADABLE)
        assertThat(unreadable.accessBanner).isFalse()
    }

    @Test
    fun `a vanished folder keeps the explorer where it was`() {
        val before = ExplorerUiState(dir = File("/a"), loading = true, entries = listOf(entry("x")))
        val after = before.listed(File("/a/gone"), Listing.NotADirectory, denied = false, mounted = emptyList(), canGoBack = true)
        assertThat(after.dir).isEqualTo(File("/a"))
        assertThat(after.entries).isEqualTo(before.entries)
        assertThat(after.loading).isFalse()
    }

    @Test
    fun `a refreshed listing keeps the selection of what is still there`() {
        val dir = File("/d")
        val selected = ExplorerUiState(selection = setOf("/d/a", "/d/gone"))
        val after = selected.listed(
            dir,
            Listing.Listed(dir, listOf(entry("a"), entry("b"))),
            denied = false,
            mounted = emptyList(),
            canGoBack = false
        )
        assertThat(after.selection).containsExactly("/d/a")
    }

    @Test
    fun `select all takes what is shown, hidden entries left out`() {
        val dir = File("/d")
        val state = ExplorerUiState().listed(dir, Listing.Listed(dir, listOf(entry(".h"), entry("a"))), false, emptyList(), false)
        assertThat(state.allSelected().selection).containsExactly("/d/a")
        assertThat(state.allSelected().toggled(entry("a")).selection).isEmpty()
        assertThat(state.sortedBy(SortMode.SIZE).sort).isEqualTo(SortMode.SIZE)
    }

    @Test
    fun `a batch that partly failed is an error, folders left out are said apart`() {
        val ok = BatchMessages.of(BatchResult(3, 0), R.plurals.copied_many)
        assertThat(ok.isError).isFalse()
        assertThat(ok.text).isEqualTo(UiText.Plural(R.plurals.copied_many, 3))
        val partial = BatchMessages.of(BatchResult(2, 1, skippedFolders = 4), R.plurals.moved_many)
        assertThat(partial.isError).isTrue()
        assertThat(partial.text).isEqualTo(
            UiText.Joined(
                UiText.Joined(UiText.Plural(R.plurals.moved_many, 2), UiText.Plural(R.plurals.errors_count, 1), R.string.joined_dot),
                UiText.Plural(R.plurals.folders_skipped, 4),
                R.string.joined_dot,
            ),
        )
    }

    @Test
    fun `a refused write reads as refused, not as "not found"`() {
        val refused = FileNotFoundException("/storage/1234-5678/a.txt: open failed: EACCES (Permission denied)")
        assertThat(IoErrors.reasonOf(refused)).isEqualTo(UiText.Res(R.string.error_access_denied))
        val readOnly = FileNotFoundException("/storage/1234-5678/a.txt: open failed: EROFS (Read-only file system)")
        assertThat(IoErrors.reasonOf(readOnly)).isEqualTo(UiText.Res(R.string.error_read_only))
        val gone = FileNotFoundException("/storage/emulated/0/a.txt: open failed: ENOENT (No such file or directory)")
        assertThat(IoErrors.reasonOf(gone)).isEqualTo(UiText.Res(R.string.error_not_found))
    }

    /** Claude review R4: without the installer of phase 3b, the system cancelled an APK without a word. */
    @Test
    fun `an APK goes to the installer's pending screen, any other file to another app`() {
        assertThat(FileRouter.targetFor(entry("app.APK"))).isEqualTo(FileTarget.Internal(Routes.pending(Feature.APK_INSTALL)))
        assertThat(FileRouter.targetFor(entry("doc.pdf"))).isEqualTo(FileTarget.External)
        assertThat(FileRouter.targetFor(entry("apk", dir = true))).isEqualTo(FileTarget.External)
    }

    @Test
    fun `only the three tile values lead anywhere`() {
        assertThat(Shortcuts.featureOf("scanner")).isEqualTo(Feature.SCANNER)
        assertThat(Shortcuts.featureOf("ocr")).isEqualTo(Feature.OCR)
        assertThat(Shortcuts.featureOf("vault")).isEqualTo(Feature.VAULT)
        listOf(null, "", "VAULT", "vault ", "settings", "../vault").forEach { assertThat(Shortcuts.featureOf(it)).isNull() }
        Shortcuts.TILE_FEATURES.forEach { assertThat(Shortcuts.featureOf(Shortcuts.valueOf(it))).isEqualTo(it) }
        assertThat(Shortcuts.TILE_FEATURES).doesNotContain(Feature.APK_INSTALL)
    }
}

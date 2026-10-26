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
import com.readfilestech.read_files_tech.ui.explorer.PickMode
import com.readfilestech.read_files_tech.ui.explorer.allSelected
import com.readfilestech.read_files_tech.ui.explorer.isVolumeParent
import com.readfilestech.read_files_tech.ui.explorer.listed
import com.readfilestech.read_files_tech.ui.explorer.sortedBy
import com.readfilestech.read_files_tech.ui.explorer.toggled
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.navigation.Shortcuts
import com.readfilestech.read_files_tech.ui.navigation.VaultScreens
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

    @Test
    fun `a filter of extensions keeps the folders, and says what it hides`() {
        val listing = Listing.Listed(File("/d"), listOf(entry("a.pdf"), entry("B.PDF"), entry("c.txt"), entry("sub", dir = true)))
        val state = ExplorerUiState(
            extensions = setOf("pdf")
        ).listed(File("/d"), listing, denied = false, mounted = emptyList(), canGoBack = false)
        assertThat(state.visible.map { it.name }).containsExactly("sub", "a.pdf", "B.PDF").inOrder()
        assertThat(state.filteredOut).isTrue()
        val onlyPdf = ExplorerUiState(extensions = setOf("pdf"))
            .listed(
                File("/d"),
                Listing.Listed(File("/d"), listOf(entry("a.pdf"))),
                denied = false,
                mounted = emptyList(),
                canGoBack = false
            )
        assertThat(onlyPdf.filteredOut).isFalse()
        assertThat(ExplorerUiState().listed(File("/d"), listing, denied = false, mounted = emptyList(), canGoBack = false).filteredOut)
            .isFalse()
    }

    @Test
    fun `the route carries the pick mode and the extensions`() {
        PickMode.entries.forEach { assertThat(PickMode.of(it.argument)).isEqualTo(it) }
        assertThat(PickMode.of(null)).isNull()
        assertThat(PickMode.of("FOLDER")).isNull()
        assertThat(Routes.extensionsOf("pdf,docx")).containsExactly("pdf", "docx")
        assertThat(Routes.extensionsOf(null)).isNull()
        assertThat(Routes.extensionsOf("")).isEmpty()
    }

    /** The folders reached by climbing the breadcrumb above a volume, which offer the volumes instead. */
    @Test
    fun `only the folders above the volumes offer the volumes`() {
        listOf("/storage", "/storage/emulated").forEach { assertThat(isVolumeParent(File(it))).isTrue() }
        listOf("/", "/storage/emulated/0", "/storage/1A2B-3C4D", "/storage/self", "/storage2", "/sdcard").forEach {
            assertThat(isVolumeParent(File(it))).isFalse()
        }
    }

    /**
     * Claude review R4: handed to the system without the app's permission, an APK was cancelled without a
     * word. "Open with…" too goes to the installer screen — the 2.15.x's went around its check (D23).
     */
    @Test
    fun `an APK goes to the installer screen, from either menu, any other file to another app`() {
        val install = FileTarget.Internal("install?path=%2Fd%2Fapp.APK")
        assertThat(FileRouter.targetFor(entry("app.APK"))).isEqualTo(install)
        assertThat(FileRouter.chooserTargetFor(entry("app.APK"))).isEqualTo(install)
        // A file no viewer opens (a PDF did, before its viewer came in phase 4).
        assertThat(FileRouter.targetFor(entry("song.mp3"))).isEqualTo(FileTarget.External)
        assertThat(FileRouter.chooserTargetFor(entry("doc.pdf"))).isEqualTo(FileTarget.External)
        assertThat(FileRouter.targetFor(entry("apk", dir = true))).isEqualTo(FileTarget.External)
    }

    /** Navigation decodes the arguments: a name with `?`, `&`, `#`, `%` or accents comes back whole. */
    @Test
    fun `route arguments are encoded as Uri encodes them`() {
        val name = "/storage/emulated/0/Été & co/a?b#c%d (1)'*!~.txt"
        val encoded = Routes.encode(name)
        assertThat(encoded).doesNotContainMatch("[^A-Za-z0-9_\\-!.~'()*%]")
        assertThat(java.net.URLDecoder.decode(encoded, Charsets.UTF_8)).isEqualTo(name)
        assertThat(Routes.encode("a b")).isEqualTo("a%20b")
        assertThat(Routes.encode("é")).isEqualTo("%C3%A9")
    }

    @Test
    fun `only the three tile values lead anywhere`() {
        assertThat(Shortcuts.featureOf("scanner")).isEqualTo(Feature.SCANNER)
        assertThat(Shortcuts.featureOf("ocr")).isEqualTo(Feature.OCR)
        assertThat(Shortcuts.featureOf("vault")).isEqualTo(Feature.VAULT)
        listOf(null, "", "VAULT", "vault ", "settings", "../vault").forEach { assertThat(Shortcuts.featureOf(it)).isNull() }
        Shortcuts.TILE_FEATURES.forEach { assertThat(Shortcuts.featureOf(Shortcuts.valueOf(it))).isEqualTo(it) }
    }

    @Test
    fun `the vault stays open on its own screens and its choosers only, a tile's tool locks it`() {
        fun keeps(route: String?, vararg arguments: Pair<String, String>) = VaultScreens.keepOpen(route, mapOf(*arguments)::get)
        assertThat(keeps(Routes.FEATURE, Routes.ARG_FEATURE to Feature.VAULT.name)).isTrue()
        assertThat(keeps(Routes.VIEWER, Routes.ARG_VIEWER to "PDF", Routes.ARG_VAULT to "x.enc")).isTrue()
        assertThat(keeps(Routes.VAULT_IMPORT, Routes.ARG_PATH to "/d")).isTrue()
        assertThat(keeps(Routes.PICKER, Routes.ARG_PICK to PickMode.FOLDER.argument)).isTrue()
        assertThat(keeps(Routes.EXPLORER, Routes.ARG_PICK to PickMode.FOLDER.argument)).isTrue()
        // What a tile, or a recent opened from the vault's picker, leads to: elsewhere.
        Feature.entries.filter { it != Feature.VAULT }.forEach {
            assertThat(keeps(Routes.FEATURE, Routes.ARG_FEATURE to it.name)).isFalse()
        }
        assertThat(keeps(Routes.VIEWER, Routes.ARG_VIEWER to "PDF", Routes.ARG_PATH to "/d/a.pdf")).isFalse()
        assertThat(keeps(Routes.EXPLORER, Routes.ARG_PATH to "/d")).isFalse()
        listOf(Routes.HOME, Routes.TRASH, Routes.SEARCH, Routes.INSTALL, Routes.LEGAL, null).forEach {
            assertThat(keeps(it)).isFalse()
        }
    }
}

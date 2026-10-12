package com.readfilestech.read_files_tech.data.viewer.image

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.browse.DirectoryListing
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.browse.SortMode
import com.readfilestech.read_files_tech.data.viewer.SourceFile
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.ui.explorer.ExplorerUiState
import com.readfilestech.read_files_tech.ui.explorer.listed
import com.readfilestech.read_files_tech.ui.navigation.FileRouter
import com.readfilestech.read_files_tech.ui.navigation.FileTarget
import com.readfilestech.read_files_tech.ui.navigation.Routes
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream

/** The image viewer's gallery: the explorer's siblings, in the explorer's order; alone from anywhere else. */
class ImageGalleryTest {
    @TempDir
    lateinit var dir: File

    private val images = ViewerKind.IMAGE.extensions

    @BeforeEach
    fun setUp() {
        // name to (size, age in hours): sorts by name, by date and by size all differ.
        mapOf(
            "b.jpg" to (300 to 1),
            "a.PNG" to (100 to 3),
            "C.gif" to (200 to 2),
            ".hidden.webp" to (50 to 0),
            "notes.txt" to (999 to 0),
            "photo.heic" to (10 to 0),
            "d.bmp" to (400 to 4),
        ).forEach { (name, sizeAndAge) ->
            File(dir, name).apply {
                writeBytes(ByteArray(sizeAndAge.first))
                setLastModified(BASE_MS - sizeAndAge.second * HOUR_MS)
            }
        }
        File(dir, "folder.jpg").mkdirs()
    }

    private fun gallery(opened: String, order: GalleryOrder?) = ImageGallery.load(StorageFileSource(File(dir, opened)), order, images)

    private fun names(gallery: Gallery) = gallery.pages.map { it.source.name }

    @Test
    fun `from the explorer, the images of the folder in its order, and the one opened`() {
        val byName = gallery("b.jpg", GalleryOrder(SortMode.NAME, showHidden = false))
        // Case ignored; no text, no HEIC (another viewer's, or none), no folder named like an image, no hidden file.
        assertThat(names(byName)).containsExactly("a.PNG", "b.jpg", "C.gif", "d.bmp").inOrder()
        assertThat(byName.start).isEqualTo(1)
        val byDate = gallery("b.jpg", GalleryOrder(SortMode.DATE, showHidden = false))
        assertThat(names(byDate)).containsExactly("b.jpg", "C.gif", "a.PNG", "d.bmp").inOrder()
        val bySize = gallery("b.jpg", GalleryOrder(SortMode.SIZE, showHidden = false))
        assertThat(names(bySize)).containsExactly("d.bmp", "b.jpg", "C.gif", "a.PNG").inOrder()
        val withHidden = gallery("b.jpg", GalleryOrder(SortMode.NAME, showHidden = true))
        assertThat(names(withHidden)).containsExactly(".hidden.webp", "a.PNG", "b.jpg", "C.gif", "d.bmp").inOrder()
        assertThat(withHidden.start).isEqualTo(2)
    }

    @Test
    fun `the explorer's name filter and extension filter hold too`() {
        // Case ignored, as the explorer: "B" is in "b.jpg" and in "d.bmp".
        val named = gallery("b.jpg", GalleryOrder(SortMode.NAME, showHidden = false, query = "B"))
        assertThat(names(named)).containsExactly("b.jpg", "d.bmp").inOrder()
        val filtered = gallery("b.jpg", GalleryOrder(SortMode.NAME, showHidden = false, extensions = setOf("jpg", "png")))
        assertThat(names(filtered)).containsExactly("a.PNG", "b.jpg").inOrder()
    }

    /** One list, the explorer's: the gallery is what its rows show, images only — whatever the order. */
    @Test
    fun `the gallery is exactly the image rows the explorer shows`() {
        for (sort in SortMode.entries) {
            for (hidden in listOf(false, true)) {
                val explorer = ExplorerUiState(sort = sort, showHidden = hidden)
                    .listed(dir, DirectoryListing.list(dir), denied = false, mounted = emptyList(), canGoBack = false)
                val rows = explorer.visible.filter { !it.isDirectory && it.extension in images }.map(FileEntry::name)
                assertThat(names(gallery("C.gif", GalleryOrder(sort, hidden)))).containsExactlyElementsIn(rows).inOrder()
            }
        }
    }

    @Test
    fun `the image opened keeps its own source, and every page its entry`() {
        val source = StorageFileSource(File(dir, "C.gif"))
        val gallery = ImageGallery.load(source, GalleryOrder(SortMode.NAME, showHidden = false), images)
        assertThat(gallery.pages[gallery.start].source).isSameInstanceAs(source)
        assertThat(gallery.pages.map { it.entry?.name }).containsExactly("a.PNG", "b.jpg", "C.gif", "d.bmp").inOrder()
    }

    @Test
    fun `from anywhere else, or not shown any more, the image alone`() {
        val home = gallery("b.jpg", order = null)
        assertThat(names(home)).containsExactly("b.jpg")
        assertThat(home.start).isEqualTo(0)
        assertThat(home.pages.single().entry?.path).isEqualTo(File(dir, "b.jpg").absolutePath)
        // A hidden file while hidden ones are not shown: alone, not the folder's first image (F18).
        assertThat(names(gallery(".hidden.webp", GalleryOrder(SortMode.NAME, showHidden = false)))).containsExactly(".hidden.webp")
        assertThat(names(gallery("gone.jpg", GalleryOrder(SortMode.NAME, showHidden = false)))).containsExactly("gone.jpg")
    }

    /** The vault's sources have no file of the storage (D-028): no siblings, no entry, no path shown. */
    @Test
    fun `a source with no file of the storage opens alone, without an entry`() {
        val vault = object : ViewerSource {
            override val name = "secret.jpg"
            override val shareable: File? = null

            override fun length() = 0L

            override fun open(cap: Long): InputStream = error("not read")

            override fun acquireFile(cap: Long): SourceFile = error("not read")
        }
        val gallery = ImageGallery.load(vault, GalleryOrder(SortMode.NAME, showHidden = false), images)
        assertThat(gallery.pages.single().source).isSameInstanceAs(vault)
        assertThat(gallery.pages.single().entry).isNull()
    }

    @Test
    fun `the order travels in the route and comes back the same`() {
        val orders = listOf(
            GalleryOrder(SortMode.NAME, showHidden = false),
            GalleryOrder(SortMode.DATE, showHidden = true, query = "a:b:c %20 é/?&#", extensions = setOf("jpg", "png")),
            GalleryOrder(SortMode.SIZE, showHidden = false, query = "*", extensions = emptySet()),
        )
        orders.forEach { assertThat(GalleryOrder.fromArgument(it.toArgument())).isEqualTo(it) }
        listOf(null, "", "NAME", "NAME:0:*", "name:0:*:", "NAME:2:*:", "NAME:true:*:").forEach {
            assertThat(GalleryOrder.fromArgument(it)).isNull()
        }
    }

    /** The explorer passes its order; only the image viewer takes it — everyone else's route is unchanged. */
    @Test
    fun `only an image opened from the explorer carries a gallery`() {
        val order = GalleryOrder(SortMode.DATE, showHidden = true, query = "x")
        fun entry(name: String) = FileEntry("/d/$name", name, false, 0, 0, isSymlink = false)
        val image = FileRouter.targetFor(entry("a.jpg"), order)
        assertThat(image).isEqualTo(FileTarget.Internal(Routes.viewer(ViewerKind.IMAGE, "/d/a.jpg", order.toArgument())))
        assertThat((image as FileTarget.Internal).route).isEqualTo("viewer/IMAGE?path=%2Fd%2Fa.jpg&gallery=DATE%3A1%3A*%3Ax")
        assertThat(FileRouter.targetFor(entry("a.jpg"))).isEqualTo(FileTarget.Internal("viewer/IMAGE?path=%2Fd%2Fa.jpg"))
        assertThat(FileRouter.targetFor(entry("a.txt"), order)).isEqualTo(FileTarget.Internal("viewer/TEXT?path=%2Fd%2Fa.txt"))
    }

    private companion object {
        const val BASE_MS = 1_760_000_000_000L
        const val HOUR_MS = 3_600_000L
    }
}

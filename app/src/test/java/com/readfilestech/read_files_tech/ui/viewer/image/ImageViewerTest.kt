package com.readfilestech.read_files_tech.ui.viewer.image

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.images.ImageTooLargeException
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.viewer.ViewerErrors
import com.readfilestech.read_files_tech.ui.viewer.ViewerFailure
import com.readfilestech.read_files_tech.ui.viewer.ViewerKind
import org.junit.jupiter.api.Test

/** The image viewer's place in the frame: its extensions, and its refusal said in words. */
class ImageViewerTest {
    /** `FileViewerRouter.imageExts` of the 2.15.3: these six, and no other (HEIC was offered, then refused — F17). */
    @Test
    fun `the image viewer opens the 2_15_3's six extensions`() {
        assertThat(ViewerKind.IMAGE.extensions).containsExactly("jpg", "jpeg", "png", "gif", "webp", "bmp")
        listOf("JPG", "photo.jpg", "heic", "svg").forEach { extension ->
            assertThat(ViewerKind.forExtension(extension)).isNotEqualTo(ViewerKind.IMAGE)
        }
    }

    /** The 2.15.3 said "Dimensions image suspectes (WxH, max …)": the size, and the limits it breaks. */
    @Test
    fun `a picture refused for its pixels is said with its size and the limits`() {
        assertThat(ViewerErrors.failureOf(ImageTooLargeException(100_000, 100_000))).isEqualTo(
            ViewerFailure.Reason(UiText.Res(R.string.viewer_image_too_large, listOf<Any>(100_000L, 100_000L, 12_000, 64L))),
        )
    }
}

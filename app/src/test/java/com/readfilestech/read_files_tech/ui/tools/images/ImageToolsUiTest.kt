package com.readfilestech.read_files_tech.ui.tools.images

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.outputs.NoOutputLocationException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.navigation.Feature
import com.readfilestech.read_files_tech.ui.navigation.Routes
import org.junit.jupiter.api.Test
import java.io.IOException

class ImageToolsUiTest {
    @Test
    fun `the explorer opens Remove EXIF on a file, its path encoded, a tool of the grid gets no path`() {
        assertThat(Routes.feature(Feature.EXIF, "/storage/emulated/0/DCIM/été #1?.jpg"))
            .isEqualTo("feature/EXIF?path=%2Fstorage%2Femulated%2F0%2FDCIM%2F%C3%A9t%C3%A9%20%231%3F.jpg")
        assertThat(Routes.feature(Feature.COMPRESS)).isEqualTo("feature/COMPRESS")
        assertThat(Routes.FEATURE).isEqualTo("feature/{feature}?path={path}")
    }

    @Test
    fun `a refusal of the decoder says why, a refusal of the file system says its reason, never the exception's text`() {
        assertThat(imageToolReason(FileTooLargeException(FileCaps.IMAGE_FILE))).isEqualTo(uiText(R.string.scanner_too_large, 100L))
        assertThat(imageToolReason(UnreadableFileException("not decoded (3)"))).isEqualTo(uiText(R.string.viewer_unreadable))
        assertThat(imageToolReason(NoOutputLocationException(IOException("No space left on device"))))
            .isEqualTo(uiText(R.string.error_io))
        assertThat(imageToolReason(IOException("write failed: ENOSPC (No space left on device)")))
            .isEqualTo(uiText(R.string.error_no_space))
        assertThat(imageToolReason(IllegalStateException("decoder"))).isEqualTo(uiText(R.string.viewer_unreadable))
    }
}

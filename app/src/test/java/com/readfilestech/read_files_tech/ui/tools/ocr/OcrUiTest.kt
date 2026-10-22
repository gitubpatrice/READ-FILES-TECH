package com.readfilestech.read_files_tech.ui.tools.ocr

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.images.ImageBounds
import com.readfilestech.read_files_tech.data.images.ImageTooLargeException
import com.readfilestech.read_files_tech.data.ocr.RecognitionFailedException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.ui.common.uiText
import org.junit.jupiter.api.Test
import java.io.IOException

class OcrUiTest {
    @Test
    fun `a picture that gives no text says why in words, never the exception's own text`() {
        // ML Kit's failure is its own, not the file's.
        assertThat(ocrReason(RecognitionFailedException(IllegalStateException("Failed to init thin text model"))))
            .isEqualTo(uiText(R.string.ocr_recognition_failed))
        // The decoder's refusals, as the image viewer and the image tools say them: 100 megapixels, too heavy, damaged.
        val maxMegapixels = FileCaps.IMAGE_PIXELS / 1_000_000
        assertThat(ocrReason(ImageTooLargeException(10_000, 10_000)))
            .isEqualTo(uiText(R.string.viewer_image_too_large, 10_000L, 10_000L, ImageBounds.MAX_SIDE_PX, maxMegapixels))
        assertThat(ocrReason(FileTooLargeException(FileCaps.IMAGE_FILE))).isEqualTo(uiText(R.string.scanner_too_large, 100L))
        assertThat(ocrReason(UnreadableFileException("empty image"))).isEqualTo(uiText(R.string.viewer_unreadable))
        assertThat(ocrReason(OutOfMemoryError("Failed to allocate"))).isEqualTo(uiText(R.string.viewer_out_of_memory))
        assertThat(ocrReason(IOException("read failed: EIO (I/O error)"))).isEqualTo(uiText(R.string.error_io))
    }
}

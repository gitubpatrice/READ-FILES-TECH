package com.readfilestech.read_files_tech.ui.tools

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.convert.Conversion
import com.readfilestech.read_files_tech.data.outputs.NoOutputLocationException
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.tools.convert.convertFailure
import org.junit.jupiter.api.Test
import java.io.IOException

/** The failures of an output every tool says the same way: Convert, ZIP, TXT, CSV, Format, OCR, Compress, EXIF, scanner. */
class ToolFailuresTest {
    @Test
    fun `no folder for the output, or no memory to make it, is said the same way by every tool`() {
        val noFolder = NoOutputLocationException(IOException("Read-only file system"))
        assertThat(outputFailure("photo.jpg", noFolder)).isEqualTo(uiText(R.string.convert_no_output))
        assertThat(outputFailure("photo.jpg", OutOfMemoryError("Failed to allocate")))
            .isEqualTo(uiText(R.string.convert_out_of_memory, "photo.jpg"))
        // Convert, which had them first, says them as before.
        assertThat(convertFailure(Conversion.IMAGE_TO_PNG, "a.png", noFolder)).isEqualTo(uiText(R.string.convert_no_output))
        assertThat(convertFailure(Conversion.IMAGE_TO_PNG, "a.png", OutOfMemoryError()))
            .isEqualTo(uiText(R.string.convert_out_of_memory, "a.png"))
    }

    @Test
    fun `any other failure is left to the tool, to say its own way`() {
        assertThat(outputFailure("x", IOException("No space left on device"))).isNull()
        assertThat(outputFailure("x", IllegalStateException("encoder"))).isNull()
    }
}

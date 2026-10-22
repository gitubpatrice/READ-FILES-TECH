package com.readfilestech.read_files_tech.ui.tools

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.convert.Conversion
import com.readfilestech.read_files_tech.data.outputs.NoOutputLocationException
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.tools.convert.convertFailure
import com.readfilestech.read_files_tech.ui.tools.csvtools.CsvWork
import com.readfilestech.read_files_tech.ui.tools.csvtools.csvToolsFailure
import org.junit.jupiter.api.Test
import java.io.IOException

/** The failures of an output every tool says the same way: Convert, ZIP, TXT, CSV, Format, OCR, Compress, EXIF, scanner. */
class ToolFailuresTest {
    private val noFolder = NoOutputLocationException(IOException("Read-only file system"))

    @Test
    fun `no folder for the output, or no memory to make it, is said the same way by every tool`() {
        assertThat(outputFailure(noFolder)).isEqualTo(uiText(R.string.convert_no_output))
        assertThat(outputFailure(OutOfMemoryError("Failed to allocate"))).isEqualTo(uiText(R.string.tool_out_of_memory))
        // Convert, which had them first, says them as before.
        assertThat(convertFailure(Conversion.IMAGE_TO_PNG, "a.png", noFolder)).isEqualTo(uiText(R.string.convert_no_output))
        assertThat(convertFailure(Conversion.IMAGE_TO_PNG, "a.png", OutOfMemoryError()))
            .isEqualTo(uiText(R.string.convert_out_of_memory, "a.png"))
    }

    @Test
    fun `the file is named only when the output converts it`() {
        assertThat(outputFailure(OutOfMemoryError(), converted = "photo.jpg"))
            .isEqualTo(uiText(R.string.convert_out_of_memory, "photo.jpg"))
        // A CSV's PDF converts the file open; a merge converts none of them, and said "convert “fusion_csv”".
        assertThat(csvToolsFailure(CsvWork.PDF, "table.csv", OutOfMemoryError()))
            .isEqualTo(uiText(R.string.convert_out_of_memory, "table.csv"))
        assertThat(csvToolsFailure(CsvWork.MERGE, "table.csv", OutOfMemoryError())).isEqualTo(uiText(R.string.tool_out_of_memory))
        assertThat(csvToolsFailure(CsvWork.MERGE, null, OutOfMemoryError())).isEqualTo(uiText(R.string.tool_out_of_memory))
    }

    @Test
    fun `any other failure is left to the tool, to say its own way`() {
        assertThat(outputFailure(IOException("No space left on device"))).isNull()
        assertThat(outputFailure(IllegalStateException("encoder"), converted = "x")).isNull()
    }
}

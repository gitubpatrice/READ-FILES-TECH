package com.readfilestech.read_files_tech.ui.tools.convert

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.convert.Conversion
import com.readfilestech.read_files_tech.data.convert.ExcelLimit
import com.readfilestech.read_files_tech.data.convert.ExcelLimitException
import com.readfilestech.read_files_tech.data.convert.ImageFailedException
import com.readfilestech.read_files_tech.data.convert.NothingToConvertException
import com.readfilestech.read_files_tech.data.convert.XlsxCells
import com.readfilestech.read_files_tech.data.images.ImageTooLargeException
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfRefusal
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfRefusalException
import com.readfilestech.read_files_tech.ui.common.UiText
import org.junit.jupiter.api.Test

/** What the Convert tool says when a conversion makes nothing: in words, and the file named — never an exception's text. */
class ConvertFailuresTest {
    @Test
    fun `nothing to convert is said for the conversion it was`() {
        val nothing = NothingToConvertException("test")

        assertThat(convertFailure(Conversion.CSV_TO_XLSX, "a.csv", nothing)).isEqualTo(UiText.Res(R.string.convert_csv_empty))
        assertThat(convertFailure(Conversion.PDF_TO_TEXT, "a.pdf", nothing)).isEqualTo(UiText.Res(R.string.convert_pdf_no_text))
        assertThat(convertFailure(Conversion.DOCX_TO_TEXT, "a.docx", nothing)).isEqualTo(UiText.Res(R.string.convert_document_empty))
    }

    @Test
    fun `of several images, the one that failed is named`() {
        val third = ImageFailedException("third.png", UnreadableFileException("test"))
        val failure = convertFailure(Conversion.IMAGES_TO_PDF, "first.jpg", third)

        assertThat(failure).isEqualTo(UiText.Res(R.string.convert_unreadable, listOf("third.png")))
    }

    @Test
    fun `an image too large says its size and the limits`() {
        val failure = convertFailure(Conversion.IMAGE_TO_JPG, "big.png", ImageTooLargeException(20_000, 10))

        assertThat((failure as UiText.Res).id).isEqualTo(R.string.convert_image_too_large)
        assertThat(failure.args.take(3)).containsExactly("big.png", 20_000L, 10L).inOrder()
    }

    @Test
    fun `Excel's limits are said with their numbers`() {
        assertThat(convertFailure(Conversion.CSV_TO_XLSX, "a.csv", ExcelLimitException(ExcelLimit.CELL_CHARS)))
            .isEqualTo(UiText.Res(R.string.convert_excel_cell, listOf(XlsxCells.MAX_CELL_CHARS)))
        assertThat(convertFailure(Conversion.CSV_TO_XLSX, "a.csv", ExcelLimitException(ExcelLimit.ROWS)))
            .isEqualTo(UiText.Res(R.string.convert_excel_rows, listOf(XlsxCells.MAX_ROWS)))
    }

    @Test
    fun `a PDF the reader stopped on is said as the viewer says it`() {
        val failure = convertFailure(Conversion.PDF_TO_TEXT, "a.pdf", PdfRefusalException(PdfRefusal.READER_STOPPED))

        assertThat(failure).isEqualTo(UiText.Res(R.string.pdf_reader_stopped))
    }
}

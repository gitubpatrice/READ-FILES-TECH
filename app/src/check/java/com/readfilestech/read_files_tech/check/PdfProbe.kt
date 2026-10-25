package com.readfilestech.read_files_tech.check

import android.content.Context
import com.readfilestech.read_files_tech.data.viewer.ViewerSource
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfRefusal
import com.readfilestech.read_files_tech.data.viewer.pdf.PdfRefusalException
import com.readfilestech.read_files_tech.ui.viewer.ViewerResources
import com.readfilestech.read_files_tech.ui.viewer.pdf.PdfPhase
import com.readfilestech.read_files_tech.ui.viewer.pdf.PdfViewer
import kotlinx.coroutines.Dispatchers

/**
 * The PDF viewer's load for the corpus (CorpusCheck): the screen's own — the file opened in the `:pdf`
 * process — then what its screen asks first: the first page and the last ("Last page"), at a phone's
 * width, and the outline. A file that ends the `:pdf` process is a refusal here (`reader_stopped`), the
 * main process alive; the screen's refusals come out as exceptions, for the corpus to name them.
 */
class PdfProbe(private val context: Context, private val width: Int, private val height: Int) {
    suspend fun load(source: ViewerSource, resources: ViewerResources): Any =
        when (val phase = PdfViewer.load(context, source, resources, Dispatchers.IO).phase.value) {
            is PdfPhase.Open -> phase.pages.also { pages ->
                for (index in setOf(0, pages.document.pageCount - 1)) {
                    pages.give(index, width, pages.take(index, width, width.toLong() * height))
                }
                pages.readOutline()
            }
            is PdfPhase.Locked -> throw PdfRefusalException(PdfRefusal.PASSWORD_REQUIRED)
            is PdfPhase.Failed -> throw PdfRefusalException(requireNotNull(phase.refusal) { "a refusal at the load" })
        }
}

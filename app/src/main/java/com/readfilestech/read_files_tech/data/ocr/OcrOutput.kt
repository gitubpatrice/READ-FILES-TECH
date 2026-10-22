package com.readfilestech.read_files_tech.data.ocr

import com.readfilestech.read_files_tech.data.outputs.OutputCategory
import com.readfilestech.read_files_tech.data.outputs.OutputStorage
import java.io.File

/** "Save as .txt" of the 2.15.3 (`ocr_screen.dart:78-87`): the text recognised, in `OCR/ocr_<stamp>.txt`. */
object OcrOutput {
    const val NAME = "ocr"
    const val EXTENSION = "txt"

    /**
     * [text] written in UTF-8, without a BOM, into a new file of `OCR/` reserved by [outputs], atomically;
     * the reservation given back if anything fails.
     *
     * @param ensureActive called once the text is written, before the file is committed: a save stopped then
     *   leaves nothing (as `Converter.output`, Claude review R18).
     */
    fun save(outputs: OutputStorage, text: String, ensureActive: () -> Unit): File {
        val file = outputs.reserve(OutputCategory.OCR, NAME, EXTENSION)
        var written = false
        try {
            outputs.write(file) { stream ->
                val writer = stream.bufferedWriter(Charsets.UTF_8)
                writer.write(text)
                writer.flush()
                ensureActive()
            }
            written = true
        } finally {
            if (!written) outputs.discard(file)
        }
        return file
    }
}

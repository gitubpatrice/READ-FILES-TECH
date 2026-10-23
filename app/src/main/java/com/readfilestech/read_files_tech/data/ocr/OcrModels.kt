package com.readfilestech.read_files_tech.data.ocr

import android.content.Context
import com.readfilestech.read_files_tech.data.files.AtomicFiles
import com.readfilestech.read_files_tech.data.tools.FileHashes
import com.readfilestech.read_files_tech.data.tools.toHex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** A file of trained data Tesseract reads: its name in `tessdata/`, its size and its SHA-256. */
internal class OcrModel(val file: String, val size: Long, val sha256: String)

/** A trained data file whose bytes are not the ones the app ships: never handed to Tesseract. */
class ModelMismatchException(file: String) : Exception("trained data $file does not match")

/**
 * The OCR's trained data (docs D-032): Tesseract's `tessdata_fast` models for French and English
 * (Apache-2.0), shipped in the APK's assets and copied ONCE into the app's no-backup files, where
 * Tesseract reads them by path — it cannot read an asset.
 *
 * Taken from github.com/tesseract-ocr/tessdata_fast at commit 87416418657359cb625c412a48b6e1d6d41c29bd
 * (2024-08-01, the head of its `main`); their sizes and SHA-256 below are those of that commit.
 *
 * Measured on the oracle (tools/oracles/ocr_oracle.py, emulator API 36, 2026-10-23), the share of words
 * read in order on the clean pages, the photo-like one, the 48 Mpx one, and a page of six other latin
 * languages (German, Spanish, Portuguese, Danish, Polish, Czech):
 *  - `fra+eng` (5.2 MB, 2.6 MB in each APK): 98.4–100 %, 100 %, 100 %; the other languages 58 %;
 *  - `eng+fra`, the same files: 98.4 % everywhere, 100 % on the photo; the other languages 55 %;
 *  - `script/Latin`, every language of the latin alphabet as ML Kit's latin model read them (the 2.15.3):
 *    100 % everywhere, the other languages too — but 89 MB (41 MB in each APK, 89 MB more copied) and
 *    1.2 to 1.7 times slower.
 * French first, then English: the app's two languages, at a seventeenth of the size. The other languages of
 * the latin alphabet lose their own letters — a regression from the 2.15.3, said in the screen's intro.
 *
 * A model is used only once its size and SHA-256 are those listed, checked each time an engine opens
 * (≈ 5 MB read). A copy is written atomically ([AtomicFiles]) and checked as it is written: a copy cut by
 * a process death leaves a temporary file, removed at the next preparation, never a model; a model whose
 * bytes changed on the disk is copied again.
 */
internal object OcrModels {
    /** Tesseract's languages, the first one primary. */
    const val LANGUAGES = "fra+eng"

    /** The assets' folder, and the one under the data path that Tesseract reads. */
    const val TESSDATA = "tessdata"

    @Suppress("MagicNumber") // the sizes of the files, as their source lists them
    val MODELS = listOf(
        OcrModel("fra.traineddata", 1_130_365, "ced037562e8c80c13122dece28dd477d399af80911a28791a66a63ac1e3445ca"),
        OcrModel("eng.traineddata", 4_113_088, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
    )

    private const val PIECE = 64 * 1024

    /**
     * The data path to give Tesseract — the app's no-backup folder, whose `tessdata/` then holds exactly
     * [MODELS], each checked. Run off the main thread; cancellable between two pieces.
     *
     * @throws java.io.IOException a model cannot be copied (the disk full, say).
     * @throws ModelMismatchException the APK's own asset is not the model listed.
     */
    suspend fun prepare(context: Context): File {
        val root = context.noBackupFilesDir
        prepare(File(root, TESSDATA), MODELS) { context.assets.open("$TESSDATA/$it") }
        return root
    }

    /** [models] in [dir], copied from [open] where missing or changed; anything else in [dir] removed. */
    suspend fun prepare(dir: File, models: List<OcrModel>, open: (String) -> InputStream) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        // A temporary file of a copy cut short, or a model no longer used.
        dir.listFiles().orEmpty().filter { file -> models.none { it.file == file.name } }.forEach { it.deleteRecursively() }
        for (model in models) {
            val target = File(dir, model.file)
            if (!isIntact(target, model)) copy(model, target, open)
        }
    }

    /** Whether [file] is [model], byte for byte as far as its SHA-256 tells. */
    suspend fun isIntact(file: File, model: OcrModel): Boolean {
        val context = currentCoroutineContext()
        return file.isFile && file.length() == model.size && FileHashes.sha256(file) { context.ensureActive() } == model.sha256
    }

    private suspend fun copy(model: OcrModel, target: File, open: (String) -> InputStream) {
        val job = currentCoroutineContext().job
        AtomicFiles.write(target) { out ->
            val digest = MessageDigest.getInstance("SHA-256")
            val written = open(model.file).use { input -> copy(input, DigestOutputStream(out, digest), job::ensureActive) }
            // What reached the file, checked before it is published under the model's name.
            if (written != model.size || digest.digest().toHex() != model.sha256) throw ModelMismatchException(model.file)
        }
        currentCoroutineContext().ensureActive()
    }

    private fun copy(input: InputStream, out: OutputStream, checkpoint: () -> Unit): Long {
        val buffer = ByteArray(PIECE)
        var total = 0L
        while (true) {
            checkpoint()
            val n = input.read(buffer)
            if (n == -1) break
            out.write(buffer, 0, n)
            total += n
        }
        out.flush()
        return total
    }
}

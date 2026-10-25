package com.readfilestech.read_files_tech.data.ocr

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.tools.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class OcrModelsTest {
    @TempDir
    lateinit var dir: File

    /** Two models of several pieces each (a piece is 64 KiB), their bytes known. */
    private val bytes = mapOf("aaa.traineddata" to bytesOf(200_000, 7), "bbb.traineddata" to bytesOf(70_000, 11))
    private val models = bytes.map { (name, data) -> OcrModel(name, data.size.toLong(), sha256(data)) }

    /** The assets as the APK holds them, counting how often each is opened. */
    private val opened = mutableListOf<String>()
    private val assets: (String) -> InputStream = { name ->
        opened += name
        ByteArrayInputStream(bytes.getValue(name))
    }

    private val tessdata get() = File(dir, "tessdata")

    private fun prepare(open: (String) -> InputStream = assets) = runBlocking { OcrModels.prepare(tessdata, models, open) }

    private fun contentOf(name: String): ByteArray = File(tessdata, name).readBytes()

    @Test
    fun `a missing model is copied from the assets, then left as it is while it is intact`() {
        prepare()
        assertThat(contentOf("aaa.traineddata")).isEqualTo(bytes.getValue("aaa.traineddata"))
        assertThat(contentOf("bbb.traineddata")).isEqualTo(bytes.getValue("bbb.traineddata"))
        assertThat(opened).containsExactly("aaa.traineddata", "bbb.traineddata")

        prepare()
        assertThat(opened).hasSize(2)
        assertThat(tessdata.list()).asList().containsExactly("aaa.traineddata", "bbb.traineddata")
    }

    @Test
    fun `a model whose bytes changed on the disk, its size the same, is copied again`() {
        prepare()
        val changed = contentOf("aaa.traineddata").also { it[100_000] = (it[100_000] + 1).toByte() }
        File(tessdata, "aaa.traineddata").writeBytes(changed)

        prepare()
        assertThat(opened).containsExactly("aaa.traineddata", "bbb.traineddata", "aaa.traineddata")
        assertThat(contentOf("aaa.traineddata")).isEqualTo(bytes.getValue("aaa.traineddata"))
    }

    @Test
    fun `a model cut short is copied again`() {
        prepare()
        File(tessdata, "bbb.traineddata").writeBytes(bytes.getValue("bbb.traineddata").copyOf(65_536))

        prepare()
        assertThat(opened.count { it == "bbb.traineddata" }).isEqualTo(2)
        assertThat(contentOf("bbb.traineddata")).isEqualTo(bytes.getValue("bbb.traineddata"))
    }

    @Test
    fun `the residue of a copy cut by a process death, or a model no longer used, is removed and never read`() {
        tessdata.mkdirs()
        // What AtomicFiles leaves when the process dies mid-copy: a temporary file next to the model's name.
        File(tessdata, "aaa.traineddata.3-9f2c41.tmp").writeBytes(bytes.getValue("aaa.traineddata").copyOf(1_000))
        File(tessdata, "Latin.traineddata").writeBytes(ByteArray(10))

        prepare()
        assertThat(tessdata.list()).asList().containsExactly("aaa.traineddata", "bbb.traineddata")
        assertThat(contentOf("aaa.traineddata")).isEqualTo(bytes.getValue("aaa.traineddata"))
    }

    @Test
    fun `an asset that is not the model listed is refused, and nothing takes the model's name`() {
        val wrong: (String) -> InputStream = { name ->
            ByteArrayInputStream(bytes.getValue(name).let { it.copyOf(it.size + 1_000) })
        }
        assertThrows<ModelMismatchException> { prepare(wrong) }
        assertThat(tessdata.list()).asList().isEmpty()

        // The same size, one byte different: the digest refuses it too.
        val altered: (String) -> InputStream = { name ->
            ByteArrayInputStream(bytes.getValue(name).copyOf().also { it[0] = (it[0] + 1).toByte() })
        }
        assertThrows<ModelMismatchException> { prepare(altered) }
        assertThat(tessdata.list()).asList().isEmpty()
    }

    @Test
    fun `a copy cancelled half-way leaves neither a model nor a temporary file`() {
        runBlocking {
            lateinit var copy: Deferred<Unit>
            // The screen leaves while the first piece is read.
            val cancelling: (String) -> InputStream = { name ->
                object : InputStream() {
                    private val data = ByteArrayInputStream(bytes.getValue(name))
                    override fun read(): Int = data.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = data.read(b, off, len).also { copy.cancel() }
                }
            }
            copy = async { OcrModels.prepare(tessdata, models, cancelling) }
            assertThat(runCatching { copy.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        }
        assertThat(tessdata.list()).asList().isEmpty()
    }

    @Test
    fun `the trained data the APK ships is the one listed, byte for byte`() {
        runBlocking {
            for (model in OcrModels.MODELS) {
                assertThat(OcrModels.isIntact(File(ASSETS, model.file), model)).isTrue()
            }
        }
        assertThat(File(ASSETS).list()).asList().containsExactlyElementsIn(OcrModels.MODELS.map { it.file })
        // One per language, named as Tesseract reads it: `<code>.traineddata`.
        assertThat(OcrLanguages.ALL.map { "${it.tesseract}.traineddata" }).isEqualTo(OcrModels.MODELS.map { it.file })
    }

    private companion object {
        /** The unit tests run in the module's folder. */
        const val ASSETS = "src/main/assets/tessdata"

        fun bytesOf(size: Int, seed: Int) = ByteArray(size) { (it * seed + it / 251).toByte() }

        fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).toHex()
    }
}

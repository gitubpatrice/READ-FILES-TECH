package com.readfilestech.read_files_tech.data.editor

import com.google.common.truth.Truth.assertThat
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.history.EditorHistory
import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CsvSavingTest {
    @TempDir
    lateinit var dir: File

    private val history by lazy { EditorHistory(File(dir, "legacy"), File(dir, "history")) }

    private fun open(file: File): Pair<CsvEdits, FileStamp> {
        val stamp = FileStamp.of(file)
        return runBlocking { CsvEdits.read(StorageFileSource(file)) } to stamp
    }

    @Test
    fun `a save writes the change and keeps the version it overwrites`() {
        val file = File(dir, "data.csv").apply { writeText("a;b\r\n1;2\r\n") }
        val (edits, stamp) = open(file)
        edits.set(1, 1, "3")
        CsvSaving.save(file, stamp, history, edits::writeTo)
        assertThat(file.readText()).isEqualTo("a;b\r\n1;3\r\n")
        val versions = history.versions(file.absolutePath)
        assertThat(versions).hasSize(1)
        assertThat(versions.single().file.readText()).isEqualTo("a;b\r\n1;2\r\n")
        assertThat(dir.list()!!.filter { it.endsWith(".tmp") }).isEmpty()
    }

    /** The rows are written back from the bytes read: over a file another app changed, they would undo its change. */
    @Test
    fun `a file changed since it was opened is not overwritten`() {
        val file = File(dir, "data.csv").apply { writeText("a,b\n1,2\n") }
        val (edits, stamp) = open(file)
        edits.set(1, 0, "9")
        file.writeText("a,b\n1,2\n3,4\n")
        assertThrows<FileChangedException> { CsvSaving.save(file, stamp, history, edits::writeTo) }
        assertThat(file.readText()).isEqualTo("a,b\n1,2\n3,4\n")
        assertThat(history.versions(file.absolutePath)).isEmpty()
        file.delete()
        assertThrows<FileChangedException> { CsvSaving.save(file, stamp, history, edits::writeTo) }
        assertThat(file.exists()).isFalse()
    }

    /** Claude review R20: the stamp was read before the copy and the writing only, seconds before the rename. */
    @Test
    fun `a file written by another app during the save is not replaced`() {
        val file = File(dir, "data.csv").apply { writeText("a,b\n1,2\n") }
        val (edits, stamp) = open(file)
        edits.set(1, 0, "9")
        assertThrows<FileChangedException> {
            CsvSaving.save(file, stamp, history) { out ->
                edits.writeTo(out)
                file.writeText("a,b\n1,2\nfrom another app\n")
            }
        }
        assertThat(file.readText()).isEqualTo("a,b\n1,2\nfrom another app\n")
        assertThat(dir.list()!!.filter { it.endsWith(".tmp") }).isEmpty()
    }

    /** Cells not saved, kept before a restore: up to the history's cap, and past it nowhere — the restore refused. */
    @Test
    fun `cells not saved are kept up to the history's cap`() {
        val file = File(dir, "data.csv").apply { writeText("a\n") }
        val cap = FileCaps.EDITOR_HISTORY_COPY.toInt()
        assertThrows<FileTooLargeException> { CsvSaving.keep(file, history) { it.write(ByteArray(cap + 1)) } }
        assertThat(history.versions(file.absolutePath)).isEmpty()
        CsvSaving.keep(file, history) { out ->
            out.write(ByteArray(cap - 1))
            out.write(0)
        }
        assertThat(history.versions(file.absolutePath).single().size).isEqualTo(cap.toLong())
        assertThrows<FileTooLargeException> {
            CsvSaving.keep(file, history) { out ->
                out.write(ByteArray(cap))
                out.write(1)
            }
        }
    }
}

package com.readfilestech.read_files_tech.data.viewer.sheet

import com.readfilestech.read_files_tech.data.viewer.StorageFileSource
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Workbooks written for the tests (by java.util.zip, never by the reader under test) and read back. */
internal object SheetTesting {
    /** A ZIP of [parts] (name to text) at [file]. */
    fun zip(file: File, vararg parts: Pair<String, String>): File {
        ZipOutputStream(file.outputStream()).use { out ->
            parts.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    fun open(file: File, limits: SheetLimits = SheetLimits()): Workbook = runBlocking {
        Workbook.open(
            StorageFileSource(file),
            limits,
            Locale.FRANCE
        )
    }

    fun sheet(workbook: Workbook, index: Int = 0): Sheet = runBlocking { workbook.sheet(index) }

    /** Every line of [sheet], the header first, each without its empty cells at the end. */
    fun lines(sheet: Sheet): List<List<String>> {
        if (sheet.isEmpty) return emptyList()
        val all = listOf(sheet.header(0, sheet.columns)) + (0 until sheet.rows).map { sheet.cells(it, 0, sheet.columns) }
        return all.map { line -> line.dropLastWhile(String::isEmpty) }
    }

    /** The lines of the first sheet of [file], the workbook closed after. */
    fun read(file: File, limits: SheetLimits = SheetLimits()): List<List<String>> = open(file, limits).use {
        lines(sheet(it))
    }
}

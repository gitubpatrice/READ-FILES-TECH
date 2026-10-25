package com.readfilestech.read_files_tech.data.storage

import java.io.File

/** Folders every phone names its own way: one rule for the home and the picker. */
object CommonFolders {
    /**
     * Samsung's then AOSP's (Pixel): the home and the picker of the 2.15.x each knew one, so one of them
     * opened a folder that was not there (inventory F30).
     */
    private val SCREENSHOTS = listOf("DCIM/Screenshots", "Pictures/Screenshots")

    /** Where this phone keeps its screenshots, under [primary]; null when neither folder is there. */
    fun screenshots(primary: File): File? = SCREENSHOTS.map { File(primary, it) }.firstOrNull(File::isDirectory)
}

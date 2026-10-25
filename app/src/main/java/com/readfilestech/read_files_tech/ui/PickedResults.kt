package com.readfilestech.read_files_tech.ui

/**
 * What the app's picker handed back to a feature's screen (its navigation entry's saved state): the
 * files, or the folder, chosen through `Routes.picker`; [consume] marks them used, so they are not
 * handed twice (a rotation, a return from another screen).
 */
class PickedResults(val files: List<String>?, val folder: String?, val consume: () -> Unit)

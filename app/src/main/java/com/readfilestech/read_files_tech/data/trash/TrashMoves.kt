package com.readfilestech.read_files_tech.data.trash

import com.readfilestech.read_files_tech.data.files.FileMoves
import com.readfilestech.read_files_tech.data.files.FileTrees
import java.io.File
import java.io.IOException

/** What only the trash does with a move; the moves themselves are [FileMoves], shared with the explorer. */
internal object TrashMoves {
    /**
     * A move into the trash whose metadata could not be written: without it the entry could never be
     * found again, so the item goes back — under a free name if its place was taken meanwhile, never
     * over what took it (GPT and Claude reviews R3). If even that fails it stays in [itemDir], kept
     * rather than deleted, and the failure joins [cause].
     */
    fun putBack(dest: File, item: File, isDir: Boolean, itemDir: File, cause: IOException) {
        try {
            val back = FileMoves.reserveUniquePath(requireNotNull(item.parentFile), item.name, isDir)
            FileMoves.move(dest, back, isDir)
            FileTrees.delete(itemDir)
        } catch (failed: IOException) {
            cause.addSuppressed(failed)
        }
    }
}

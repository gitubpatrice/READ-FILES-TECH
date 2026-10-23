package com.readfilestech.read_files_tech.data.viewer

import com.readfilestech.read_files_tech.data.files.BoundedInputStream
import com.readfilestech.read_files_tech.data.files.FileCaps
import com.readfilestech.read_files_tech.data.files.FileTooLargeException
import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.vault.VaultCipher
import com.readfilestech.read_files_tech.vault.VaultFiles
import com.readfilestech.read_files_tech.vault.format.VaultNames
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A file of the vault, read inside the app (docs D-028): through a private copy of its plaintext in
 * `cache/vault_decrypt/` ([VaultFiles.copy] for a viewer, its tag checked before the copy exists), made at
 * each opening and deleted when its stream or its handle closes — and at the latest when the vault locks
 * (VaultService.purgeDecrypted), which also closes the viewer. Never handed to another app: no [shareable].
 */
class VaultFileSource(private val files: VaultFiles, private val cipher: VaultCipher, private val encrypted: File) : ViewerSource {
    /** Its name in the vault, a control character a 2.15.x let in made `_` as for its copy. */
    override val name: String = VaultNames.readableName(encrypted.name) ?: VaultNames.plainName(encrypted.name)

    override val shareable: File? get() = null

    override fun length(): Long = cipher.plainLength(encrypted)

    override fun open(cap: Long): InputStream {
        val copy = acquireFile(cap)
        return try {
            val stream = object : FilterInputStream(FileInputStream(copy.file)) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        copy.close()
                    }
                }
            }
            BoundedInputStream(stream, cap)
        } catch (e: IOException) {
            copy.close()
            throw e
        }
    }

    /**
     * A private copy, its own folder deleted when the handle closes. Refused before anything is decrypted
     * when the size already says so; the copy is checked again, a vault file being replaced meanwhile.
     */
    override fun acquireFile(cap: Long): SourceFile {
        if (length() > cap) throw FileTooLargeException(cap)
        val plain = files.copy(encrypted, VaultFiles.Copy.VIEWER)
        val handle = SourceFile(plain) { FileTrees.delete(plain.parentFile ?: plain) }
        try {
            FileCaps.check(plain, cap)
        } catch (e: FileTooLargeException) {
            handle.close()
            throw e
        }
        return handle
    }
}

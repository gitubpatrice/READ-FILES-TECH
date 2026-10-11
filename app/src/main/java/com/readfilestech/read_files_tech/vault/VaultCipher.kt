package com.readfilestech.read_files_tech.vault

import com.readfilestech.read_files_tech.data.files.LimitedInput
import com.readfilestech.read_files_tech.vault.format.Gcm
import com.readfilestech.read_files_tech.vault.format.VaultCrypto
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/** Where the vault lives: the Flutter app's own directories, read in place (docs D-003). */
class VaultDirs(
    /** `app_flutter/vault/`: `getApplicationDocumentsDirectory()` of path_provider is `getDir("flutter")`. */
    val vault: File,
    /** `cache/`: where a file opened from the vault is decrypted for a viewer or a share. */
    val cache: File,
)

/**
 * Encrypting and decrypting vault files, by the engine their size allows (docs D-023): one block when
 * three copies fit in a twelfth of the heap, a stream otherwise.
 */
@Singleton
class VaultCipher @Inject constructor(private val memory: DeviceMemory) {
    private val random = SecureRandom()

    fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    fun fitsInOneBlock(bytes: Long) = Gcm.fitsInOneBlock(bytes, memory.maxHeapBytes)

    /**
     * The plaintext of the vault file [file] into [out]. [name] is the one in its AAD: the file's own,
     * except for a re-encrypted copy checked under a temporary name before it takes the real one. On
     * the stream path [out] receives UNVERIFIED bytes until this returns: callers write to a temporary
     * file and publish only after.
     *
     * @throws com.readfilestech.read_files_tech.vault.format.VaultAuthenticationException
     * @throws com.readfilestech.read_files_tech.vault.format.VaultFormatException
     */
    @Suppress("LongParameterList") // the file, the name it answers to, the key and flag that open it, the output
    fun decryptFile(file: File, key: ByteArray, v2Only: Boolean, out: OutputStream, name: String = file.name) {
        val length = file.length()
        if (fitsInOneBlock(length)) {
            val plain = VaultCrypto.decryptAuto(file.readBytes(), key, name, v2Only)
            try {
                out.write(plain)
            } finally {
                plain.fill(0)
            }
        } else {
            FileInputStream(file).use { VaultCrypto.decryptAuto(it, length, out, key, name, v2Only) }
        }
    }

    /**
     * Writes into [out] the v2 vault file named [fileName] holding the [size] bytes of [input]. Reading
     * more or fewer bytes than [size] is refused: the source changed under the operation.
     */
    fun encrypt(input: InputStream, size: Long, key: ByteArray, fileName: String, out: OutputStream) {
        val nonce = randomBytes(VaultCrypto.NONCE_LEN)
        val exact = LimitedInput(input, size)
        if (fitsInOneBlock(size)) {
            val plain = ByteArray(size.toInt())
            try {
                DataInputStream(exact).readFully(plain)
                exact.ensureEnd()
                out.write(VaultCrypto.encryptV2(plain, key, nonce, fileName))
            } finally {
                plain.fill(0)
            }
        } else {
            VaultCrypto.encryptV2(exact, out, key, nonce, fileName)
            exact.ensureEnd()
        }
    }

    /** The first bytes of [file], up to [count]: enough to tell a v2 blob or read a backup header. */
    fun head(file: File, count: Int): ByteArray = FileInputStream(file).use { input ->
        val buffer = ByteArray(minOf(count.toLong(), file.length()).toInt())
        DataInputStream(input).readFully(buffer)
        buffer
    }
}

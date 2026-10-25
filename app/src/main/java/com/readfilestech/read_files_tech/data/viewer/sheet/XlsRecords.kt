package com.readfilestech.read_files_tech.data.viewer.sheet

import com.readfilestech.read_files_tech.data.viewer.UnsupportedFormatException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.poi.hssf.record.BOFRecord
import org.apache.poi.hssf.record.EOFRecord
import org.apache.poi.hssf.record.FilePassRecord
import org.apache.poi.hssf.record.RecordInputStream
import org.apache.poi.hssf.record.WriteProtectRecord
import org.apache.poi.hssf.record.crypto.Biff8DecryptingStream
import org.apache.poi.poifs.crypt.Decryptor
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import java.security.GeneralSecurityException

/**
 * The workbook stream of an .xls (the stream [entry] of the OLE file [ole]), read from its start as many
 * times as asked — once for the workbook's globals, once for a sheet's cells, once for the shared strings
 * they use: nothing of the stream is kept between two passes. Decrypted when the workbook is.
 */
internal class XlsStream private constructor(
    private val ole: POIFSFileSystem,
    private val entry: String,
    private val encryption: Encryption?,
) {
    /** One pass over the records, from the first: [read] moves through them; the stream is closed after. */
    suspend fun <T> pass(read: suspend (XlsRecords) -> T): T = ole.root.createDocumentInputStream(entry).use { input ->
        val records = if (encryption == null) {
            RecordInputStream(input)
        } else {
            // The records before the encrypted ones are left as they are; the key stream starts where they end.
            input.readFully(ByteArray(encryption.offset))
            RecordInputStream(input, encryption.info, encryption.offset)
        }
        read(XlsRecords(records))
    }

    /** A workbook stream that Excel's default password opens, its encrypted records from [offset]. */
    private class Encryption(val info: EncryptionInfo, val offset: Int)

    companion object {
        /**
         * The workbook stream [entry] of [ole]. Encryption is told as POI's `RecordFactoryInputStream` tells it,
         * whose decrypting stream is private: a FILEPASS right after the BOF, or after a WRITEPROTECT. Excel's
         * default password opens it ("VelvetSweatshop": a workbook protected without a password to open, as
         * POI read it); any other password makes it a format not opened.
         */
        suspend fun open(ole: POIFSFileSystem, entry: String): XlsStream =
            XlsStream(ole, entry, XlsStream(ole, entry, null).pass(::encryption))

        private suspend fun encryption(records: XlsRecords): Encryption? {
            val afterBof = records.next() && records.sid == BOFRecord.sid && records.next()
            val filePass = afterBof && (records.sid != WriteProtectRecord.sid || records.next()) && records.sid == FilePassRecord.sid
            return if (filePass) opened(FilePassRecord(records.input).encryptionInfo, records.offset) else null
        }

        private fun opened(info: EncryptionInfo, offset: Int): Encryption {
            val opens = try {
                info.decryptor.verifyPassword(Decryptor.DEFAULT_PASSWORD)
            } catch (e: GeneralSecurityException) {
                throw UnsupportedFormatException("an .xls encryption this device does not run").apply { initCause(e) }
            }
            if (!opens) throw UnsupportedFormatException("an .xls encrypted with a password")
            return Encryption(info, offset)
        }
    }
}

/**
 * The records of a workbook stream, in order ([next]), each at most 8 224 bytes (BIFF8's): what a reader
 * leaves unread of one is skipped when it moves on. Cancellable between two records.
 */
internal class XlsRecords(val input: RecordInputStream) {
    /** The type of the record [next] moved to. */
    var sid: Short = 0
        private set

    /** Bytes from the stream's start to the end of the record [next] moved to (its CONTINUEs read through aside). */
    var offset = 0
        private set

    private var count = 0
    private val skipped = ByteArray(RecordInputStream.MAX_RECORD_DATA_SIZE.toInt())

    /** Moves to the next record; false at the stream's end. */
    suspend fun next(): Boolean {
        skipRest()
        if (++count % CANCEL_CHECK_EVERY == 0) currentCoroutineContext().ensureActive()
        if (!input.hasNextRecord()) return false
        input.nextRecord()
        sid = input.sid
        offset += HEADER_BYTES + input.remaining()
        return true
    }

    /**
     * What a reader left of the current record. POI's decrypting stream reads a record it never encrypts (BOF,
     * INTERFACEHDR) to the END OF THE BUFFER it is given, whatever length it is asked for (POI 5.5.1,
     * `Biff8DecryptingStream.readFully`): such a record is skipped through a buffer of its own size — read
     * through the shared one, it took the next records with it (measured on a workbook Excel encrypted).
     */
    private fun skipRest() {
        val left = input.remaining()
        if (left == 0) return
        input.readFully(if (Biff8DecryptingStream.isNeverEncryptedRecord(input.sid.toInt())) ByteArray(left) else skipped, 0, left)
    }

    /** Skips the workbook's globals, the records up to their EOF: the first substream comes next. */
    suspend fun skipGlobals() {
        var more = next()
        while (more && sid != EOFRecord.sid) more = next()
    }

    /** Skips the rest of the substream whose BOF was just read, the substreams nested in it included. */
    suspend fun skipSubstream() {
        var depth = 1
        while (depth > 0 && next()) {
            when (sid) {
                BOFRecord.sid -> depth++
                EOFRecord.sid -> depth--
            }
        }
    }

    private companion object {
        /** A record's type and length. */
        const val HEADER_BYTES = 4
        const val CANCEL_CHECK_EVERY = 4096
    }
}

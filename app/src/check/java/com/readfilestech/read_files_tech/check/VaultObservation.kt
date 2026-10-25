package com.readfilestech.read_files_tech.check

import com.readfilestech.read_files_tech.data.files.FileTrees
import com.readfilestech.read_files_tech.vault.VaultBackups
import com.readfilestech.read_files_tech.vault.VaultCorruptedException
import com.readfilestech.read_files_tech.vault.VaultKeys
import com.readfilestech.read_files_tech.vault.VaultLockoutException
import com.readfilestech.read_files_tech.vault.ZeroKeyRepair
import com.readfilestech.read_files_tech.vault.format.VaultNames
import java.io.File

/**
 * The vault as the app will open it: set up or not, the two lockouts (D2, docs D-016), the unlock with
 * the right password — refused while a lockout runs —, the one-off repair (D1), each file decrypted by
 * the app's own path, and each `.rftvault` restored. Ends locked, decrypted copies purged.
 */
class VaultObservation(private val deps: PortCheckEntryPoint) {
    suspend fun observe(request: PortCheckRequest): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        out["setUp"] = attempt { deps.vault().isSetUp() }
        out["lockouts"] = linkedMapOf("unlock" to lockout(VaultKeys.UNLOCK_LOCKOUT), "backup" to lockout(VaultKeys.BACKUP_LOCKOUT))
        val password = request.vaultPassword
        if (password != null) {
            val unlocked = attempt { deps.vault().unlock(password) }
            out["unlock"] = unlocked
            if (unlocked == true) out.putAll(unlockedState(request))
        }
        deps.vault().lock()
        deps.vault().purgeDecrypted()
        return out
    }

    private suspend fun unlockedState(request: PortCheckRequest): Map<String, Any?> = linkedMapOf(
        "repair" to if (request.repair) attempt { deps.repair().runOnce()?.let(::repairOf) } else "notRequested",
        "pendingReport" to attempt { deps.repair().pendingReport()?.let(::repairOf) },
        "files" to files(),
        "restores" to request.restores.map { restore(it, request.backupPassword) },
    )

    /** Its remaining time at an instant read once, and that instant: the host recomputes it from the seed. */
    private fun lockout(lockout: VaultKeys.Lockout): Map<String, Any?> {
        val at = deps.lockouts().now()
        val remaining: Any? = try {
            deps.lockouts().ensureFree(lockout, at)
            0L
        } catch (e: VaultLockoutException) {
            e.remainingMs
        } catch (e: VaultCorruptedException) {
            failure(e)
        }
        return linkedMapOf("remainingMs" to remaining, "wallMs" to at.wallMs, "monoMs" to at.monoMs)
    }

    /** Every file of the vault, decrypted by the app's own path to the cache, hashed, then removed. */
    private suspend fun files(): Map<String, Any?> {
        val listed = deps.vaultFiles().list().sortedBy { it.name }
        val out = linkedMapOf<String, Any?>()
        for (encrypted in listed) out[VaultNames.plainName(encrypted.name)] = attempt { digestOfDecrypted(encrypted) }
        return out
    }

    private suspend fun digestOfDecrypted(encrypted: File): Map<String, Any?> {
        val plain = deps.vaultFiles().decryptToCache(encrypted)
        try {
            return linkedMapOf("size" to plain.length(), "sha256" to Digests.sha256(plain))
        } finally {
            // Its slot folder (cache/vault_decrypt/<slot>/), not only the file.
            FileTrees.delete(plain.parentFile ?: plain)
        }
    }

    private suspend fun restore(path: String, password: String?): Map<String, Any?> {
        val result = attempt {
            restoreOf(deps.backups().restore(File(path), requireNotNull(password), overwriteExisting = true))
        }
        return linkedMapOf("path" to path, "result" to result, "files" to files())
    }

    private fun repairOf(result: ZeroKeyRepair.Result) = linkedMapOf(
        "scanned" to result.scanned,
        "repaired" to result.repaired,
        "unexamined" to result.unexamined,
    )

    private fun restoreOf(result: VaultBackups.RestoreResult) = linkedMapOf(
        "total" to result.total,
        "restored" to result.restored,
        "skipped" to result.skipped,
        "failed" to result.failed,
        "refused" to result.refused.map { linkedMapOf("name" to it.name, "reason" to it.reason) },
    )
}

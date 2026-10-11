package com.readfilestech.read_files_tech.data.sharing

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether an app is installed, and whether it is really the one we know — by its signing certificate,
 * since anyone can publish an app under any package name outside the Play Store.
 *
 * Used before granting WRITE access to a file (the PDF Tech bridge, plan phase 3, finding 23): the
 * 2.15.x granted it to whatever app was installed as `com.pdftech.pdf_tech`.
 */
@Singleton
class AppSignatures @Inject constructor(@ApplicationContext private val context: Context) {
    private val packages: PackageManager = context.packageManager

    fun isInstalled(packageName: String): Boolean = try {
        packages.getPackageInfo(packageName, 0)
        true
    } catch (expected: PackageManager.NameNotFoundException) {
        false
    }

    /** [packageName] is installed AND signed by the certificate whose SHA-256 is [sha256] (64 hex digits). */
    fun isSignedBy(packageName: String, sha256: String): Boolean {
        val certificate = hexToBytes(sha256)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Its whole signing history: a rotated key still proves the app.
                packages.hasSigningCertificate(packageName, certificate, PackageManager.CERT_INPUT_SHA256)
            } else {
                legacySigners(packageName).let { it.size == 1 && it.single().contentEquals(certificate) }
            }
        } catch (expected: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Android 8.x: the certificates of GET_SIGNATURES, hashed. One signer only is trusted. */
    @Suppress("DEPRECATION")
    @SuppressLint("PackageManagerGetSignatures")
    private fun legacySigners(packageName: String): List<ByteArray> =
        packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length == SHA256_HEX_LENGTH) { "a SHA-256 is $SHA256_HEX_LENGTH hex digits" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(HEX).toByte() }
    }

    private companion object {
        const val SHA256_HEX_LENGTH = 64
        const val HEX = 16
    }
}

/** The apps the explorer sends files to: their package, and for PDF Tech, the certificate it is signed with. */
object KnownApps {
    const val PDF_TECH = "com.pdftech.pdf_tech"

    /** PDF Tech's release certificate: `apksigner verify --print-certs` on pdf-tech-x86_64-1.13.6.apk (GitHub release). */
    const val PDF_TECH_CERT_SHA256 = "7d2c119973830ac791011a740ca736ac1b59009a3e7599cecf7d21c82dd2a4f4"

    const val KDRIVE = "com.infomaniak.drive"
    const val PROTON_DRIVE = "me.proton.android.drive"
}

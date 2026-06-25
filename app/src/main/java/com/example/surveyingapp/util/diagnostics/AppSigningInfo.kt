package com.example.surveyingapp.util.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * Reads the *installed* app's signing-certificate fingerprints — the SHA-1/SHA-256 that must be
 * registered in the Google Cloud Console "Android apps" restriction for the Maps API key. Works for
 * debug and release builds and APKs installed by Android Studio or shared with testers; never
 * assumes a hardcoded value, and never exposes private keys / keystore paths.
 */
object AppSigningInfo {

    data class Fingerprints(val sha1: String, val sha256: String)

    /** Returns the current signing cert's SHA-1 + SHA-256, or null if it can't be read. */
    fun fingerprints(context: Context): Fingerprints? = try {
        val cert = currentSignerCertBytes(context)
        if (cert == null) null
        else Fingerprints(
            sha1 = formatFingerprint(digest(cert, "SHA-1")),
            sha256 = formatFingerprint(digest(cert, "SHA-256"))
        )
    } catch (_: Exception) {
        null
    }

    private fun currentSignerCertBytes(context: Context): ByteArray? {
        val pm = context.packageManager
        val pkg = context.packageName
        @Suppress("PackageManagerGetSignatures")
        val signatures: Array<Signature>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
        return signatures?.firstOrNull()?.toByteArray()
    }

    private fun digest(bytes: ByteArray, algorithm: String): ByteArray =
        MessageDigest.getInstance(algorithm).digest(bytes)

    /** Formats a digest as upper-case colon-separated hex (matches keytool / `signingReport`). Pure. */
    fun formatFingerprint(bytes: ByteArray): String =
        bytes.joinToString(":") { "%02X".format(it) }
}

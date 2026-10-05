package com.questoverlay

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Tells the official app apart from a repackaged copy. Anyone who copies the app has to sign it
 * with their own key, because the release key never leaves this project's encrypted store, so
 * a different signing certificate means the copy didn't come from us.
 *
 * If the Google Play version is ever re-signed by Google (Play App Signing with a Google-made
 * key), add that key's SHA-256 here too. Uploading our own release key to Play avoids that.
 */
object Authenticity {

    /** SHA-256 of the Breadcrumbs release signing certificate. */
    private val OFFICIAL = setOf(
        "338864795" + "4D4D93D08F3D23EE406297685" + "7DB9BE0445554B2452C67C6022F60B"
    )

    @Volatile private var cached: Boolean? = null

    fun isOfficial(context: Context): Boolean {
        cached?.let { return it }
        val ok = try {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signing = info.signingInfo
            val certs = when {
                signing == null -> emptyArray()
                signing.hasMultipleSigners() -> signing.apkContentsSigners
                else -> signing.signingCertificateHistory
            }
            certs.any { sha256(it.toByteArray()) in OFFICIAL }
        } catch (e: Exception) {
            false
        }
        cached = ok
        return ok
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
}

package com.glowled.app.util

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Tells whether this install is the official signed release. Android only installs an update over
 * an app signed with the same key, so users can check this before downloading a new version.
 */
object AppSignature {

    /** SHA-256 of the Glow LED release signing certificate. */
    const val RELEASE_SHA256 = "5d04de1a96a80107ec87658382b063f15c53cfea8043f554a339f285fe0c5023"

    fun sha256(context: Context): String? = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners
        signers?.firstOrNull()?.toByteArray()?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    } catch (_: Exception) {
        null
    }

    fun isOfficial(context: Context) = sha256(context) == RELEASE_SHA256

    /** e.g. "5D:04:DE:1A…C5:02:3" style short form for display. */
    fun short(hex: String?): String = hex?.uppercase()?.chunked(2)?.let {
        it.take(4).joinToString(":") + "…" + it.takeLast(2).joinToString(":")
    } ?: "unknown"
}

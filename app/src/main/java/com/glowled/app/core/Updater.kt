package com.glowled.app.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.glowled.app.BuildConfig
import com.glowled.app.util.AppSignature
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The in-app updater: asks GitHub for the latest release, downloads its APK, checks it is a newer
 * Glow LED signed with the official release key, and hands it to the system installer.
 * Checking for updates is the only thing Glow LED uses the internet for.
 */
object Updater {

    private const val API = "https://api.github.com/repos/ankitchanki/LED/releases/latest"
    const val RELEASES_PAGE = "https://github.com/ankitchanki/LED/releases"
    private const val APK_NAME = "GlowLED-latest.apk"
    const val ACTION_INSTALL_STATUS = "com.glowled.app.UPDATE_INSTALL_STATUS"

    // Last result, kept in prefs so the Settings row and the notification can show it.
    const val K_LATEST = "update_latest"
    const val K_NOTES = "update_notes"
    const val K_CHECKED = "update_checked"
    const val K_NOTIFIED = "update_notified"
    const val K_AUTO = "update_check"

    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    /** Fetches the latest release (network: call off the main thread). */
    @Throws(IOException::class)
    fun fetchLatest(): Release {
        val json = JSONObject(get(API).bufferedReader().use { it.readText() })
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.optJSONArray("assets")
        var url = ""
        var size = 0L
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == APK_NAME) {
                url = a.optString("browser_download_url")
                size = a.optLong("size")
            }
        }
        if (url.isEmpty()) throw IOException("no $APK_NAME in release $version")
        return Release(version, json.optString("body"), url, size)
    }

    /** Checks and remembers the result; returns the release, or null when the check failed. */
    fun check(context: Context): Release? = try {
        fetchLatest().also { r ->
            LedController.prefs.sp.edit()
                .putString(K_LATEST, r.version)
                .putString(K_NOTES, r.notes)
                .putLong(K_CHECKED, System.currentTimeMillis())
                .apply()
        }
    } catch (e: Exception) {
        DebugLog.add("Update check failed: ${e.message}")
        null
    }

    /** The newest version seen by the last check, if it is newer than this one. */
    fun pendingVersion(): String? =
        LedController.prefs.sp.getString(K_LATEST, null)?.takeIf { isNewer(it, BuildConfig.VERSION_NAME) }

    private fun get(url: String): java.io.InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "GlowLED/${BuildConfig.VERSION_NAME}")
        if (c.responseCode !in 200..299) {
            c.disconnect()
            throw IOException("HTTP ${c.responseCode}")
        }
        return c.inputStream
    }

    /** "2.10.0" is newer than "2.9.1"; anything after a "-" (like "-debug") is ignored. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Release notes as plain text: drops the version heading and the signed-build line, turns
     * Markdown headings into plain lines and list items into bullets, removes emphasis marks and
     * re-joins lines that were only wrapped for width.
     */
    fun plainNotes(body: String): String {
        val out = mutableListOf<String>()
        var joinable = false
        for (raw in body.lines()) {
            val line = raw.trimEnd()
            val t = line.trim()
            when {
                t.startsWith("# ") || t.startsWith("✅") || t.startsWith("later and keeps your settings") -> joinable = false
                t.isEmpty() -> { if (out.isNotEmpty() && out.last().isNotEmpty()) out += ""; joinable = false }
                t.startsWith("#") -> { out += t.trimStart('#').trim(); joinable = false }
                Regex("^[-*]\\s+").containsMatchIn(t) -> { out += "• " + t.replaceFirst(Regex("^[-*]\\s+"), ""); joinable = true }
                joinable -> out[out.lastIndex] = out.last() + " " + t
                else -> { out += t; joinable = true }
            }
        }
        return out.joinToString("\n") { line ->
            line.replace("**", "").replace("`", "")
                .replace(Regex("(?<![\\w*])\\*([^*\\n]+)\\*(?![\\w*])"), "$1")
        }.trim()
    }

    // ---- download and install ---------------------------------------------------------------------

    /** Downloads the release APK into the cache; [progress] gets 0..1. Throws on failure or cancel. */
    @Throws(IOException::class)
    fun download(context: Context, r: Release, cancelled: () -> Boolean, progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "GlowLED-${r.version}.apk")
        get(r.apkUrl).use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    if (cancelled()) throw IOException("cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (r.size > 0) progress((done.toFloat() / r.size).coerceIn(0f, 1f))
                }
            }
        }
        return file
    }

    /**
     * True when [apk] is Glow LED, newer than this install and signed with the official release
     * key. Android refuses a different key anyway; this stops anything else before the prompt.
     */
    fun verify(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (info.packageName != context.packageName) return false
        if (info.longVersionCode <= BuildConfig.VERSION_CODE) return false
        @Suppress("DEPRECATION")
        val certs = info.signingInfo?.apkContentsSigners ?: info.signatures ?: return false
        val sha = MessageDigest.getInstance("SHA-256")
        return certs.isNotEmpty() && certs.all {
            sha.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } == AppSignature.RELEASE_SHA256
        }
    }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Streams [apk] into a system install session; the system then asks the user to confirm. */
    @Throws(IOException::class)
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_INSTALL_STATUS),
                // The system adds the result to this intent, so it has to stay mutable.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
    }

    /** Listener for the install result (the update screen shows it). */
    var onInstallResult: ((ok: Boolean, message: String?) -> Unit)? = null

    /** Receives the installer's answer: shows its confirmation screen, or reports the result. */
    class InstallStatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                        try { context.startActivity(it) } catch (e: Exception) { DebugLog.add("Install prompt failed: ${e.message}") }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> onInstallResult?.invoke(true, null)
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                    DebugLog.add("Update install failed: $msg")
                    onInstallResult?.invoke(false, msg)
                }
            }
        }
    }
}

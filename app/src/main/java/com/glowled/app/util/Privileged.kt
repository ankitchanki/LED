package com.glowled.app.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.glowled.app.core.DebugLog
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Optional shell access through Shizuku (no root) or root, always approved by the user in the
 * Shizuku app / root manager. Used for:
 *  - one-tap setup: grants Glow LED's own permissions (no Settings hunting, no "restricted settings");
 *  - switching the screen off without the accessibility service.
 * Every command only touches Glow LED's own package.
 */
object Privileged {

    enum class Backend { NONE, SHIZUKU, ROOT }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val SHIZUKU_CODE = 7301

    const val PREF_ROOT_OK = "root_ok"

    @Volatile
    private var rootGranted = false

    fun markRootGranted() {
        rootGranted = true
    }

    // ---- availability ---------------------------------------------------------------------------

    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    } catch (_: Throwable) {
        false
    }

    fun shizukuGranted(): Boolean = shizukuRunning() && try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Cheap check for a su binary; the real test is [requestRoot]. */
    fun rootLikely(): Boolean = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "/debug_ramdisk/su",
    ).any { File(it).exists() }

    fun backend(): Backend = when {
        shizukuGranted() -> Backend.SHIZUKU
        rootGranted -> Backend.ROOT
        else -> Backend.NONE
    }

    /** Shows Shizuku's own permission dialog; [done] receives true when allowed. */
    fun requestShizuku(done: (Boolean) -> Unit) {
        if (!shizukuRunning()) return done(false)
        if (shizukuGranted()) return done(true)
        val l = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != SHIZUKU_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                main.post { done(grantResult == PackageManager.PERMISSION_GRANTED) }
            }
        }
        Shizuku.addRequestPermissionResultListener(l)
        Shizuku.requestPermission(SHIZUKU_CODE)
    }

    /** Triggers the root manager prompt; [done] receives true when su works. */
    fun requestRoot(done: (Boolean) -> Unit) = io.execute {
        val ok = runRoot("id").first == 0
        rootGranted = ok
        main.post { done(ok) }
    }

    // ---- execution ------------------------------------------------------------------------------

    /** Runs a shell command with the active backend. Blocking: call off the main thread. */
    private fun exec(cmd: String): Pair<Int, String> = when (backend()) {
        Backend.SHIZUKU -> runShizuku(cmd)
        Backend.ROOT -> runRoot(cmd)
        Backend.NONE -> -1 to "no shell access"
    }

    private fun runRoot(cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroy()
        p.exitValue() to out.trim()
    } catch (e: Exception) {
        -1 to (e.message ?: "su failed")
    }

    private fun runShizuku(cmd: String): Pair<Int, String> = try {
        // Shizuku.newProcess isn't public in API 13, but it is the simplest way to run one command.
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor()
        p.exitValue() to out.trim()
    } catch (e: Throwable) {
        -1 to (e.cause?.message ?: e.message ?: "shizuku failed")
    }

    // ---- features -------------------------------------------------------------------------------

    /** Switches the screen off like the power button. Returns false when no shell access. */
    fun screenOff(): Boolean {
        if (backend() == Backend.NONE) return false
        io.execute { exec("input keyevent 223") } // KEYCODE_SLEEP
        return true
    }

    /**
     * Grants Glow LED's own permissions in one go. Reports a one-line summary on the main thread.
     */
    fun setup(context: Context, done: (String) -> Unit) = io.execute {
        val pkg = context.packageName
        val listener = "$pkg/com.glowled.app.service.NotifyListenerService"
        val a11y = "$pkg/com.glowled.app.service.LedAccessibilityService"
        val steps = mutableListOf<Pair<String, String>>()
        if (Build.VERSION.SDK_INT >= 33) {
            steps += "Restricted settings" to "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow"
            steps += "Notifications" to "pm grant $pkg android.permission.POST_NOTIFICATIONS"
        }
        steps += "Notification access" to "cmd notification allow_listener $listener"
        steps += "Display over apps" to "appops set $pkg SYSTEM_ALERT_WINDOW allow"
        steps += "Battery unrestricted" to "cmd deviceidle whitelist +$pkg"
        if (Build.VERSION.SDK_INT >= 34) {
            steps += "Full-screen alerts" to "appops set $pkg USE_FULL_SCREEN_INTENT allow"
            steps += "Turn screen on" to "appops set $pkg TURN_SCREEN_ON allow"
        }
        if (OemHelper.isXiaomi) {
            // MIUI/HyperOS private ops: show on lock screen, background pop-ups, autostart.
            steps += "Xiaomi lock screen" to "appops set $pkg 10020 allow"
            steps += "Xiaomi pop-ups" to "appops set $pkg 10021 allow"
            steps += "Xiaomi autostart" to "appops set $pkg 10008 allow"
        }
        var ok = 0
        steps.forEach { (name, cmd) ->
            val (code, out) = exec(cmd)
            if (code == 0) ok++ else DebugLog.add("Setup: $name failed ($out)")
        }
        // Accessibility: append our service, keeping any others the user already enabled.
        val current = exec("settings get secure enabled_accessibility_services").second
            .takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        val a11yOk = if (current.split(':').any { it.equals(a11y, ignoreCase = true) }) {
            true
        } else {
            val list = if (current.isEmpty()) a11y else "$current:$a11y"
            exec("settings put secure enabled_accessibility_services '$list'").first == 0 &&
                exec("settings put secure accessibility_enabled 1").first == 0
        }
        val summary = "One-tap setup via ${backend().name.lowercase()}: $ok of ${steps.size} permissions" +
            if (a11yOk) " + accessibility" else " (accessibility failed - enable it manually)"
        DebugLog.add(summary)
        main.post { done(summary) }
    }
}

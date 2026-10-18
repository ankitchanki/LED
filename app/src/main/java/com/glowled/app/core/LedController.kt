package com.glowled.app.core

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.glowled.app.R
import com.glowled.app.service.AodOverlay
import com.glowled.app.service.LedAccessibilityService
import com.glowled.app.service.NotifyListenerService
import com.glowled.app.service.OverlayHost
import com.glowled.app.service.PulseReceiver
import com.glowled.app.ui.AodActivity
import com.glowled.app.util.OemHelper
import com.glowled.app.util.Privileged
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Decides when to light the LED:
 *  - screen on  → small overlay around the camera (accessibility overlay, or "draw over apps" fallback);
 *  - screen off → black always-on screen ([AodActivity]) at minimum brightness and refresh rate.
 *
 * All work is event driven (notification posted/removed, screen on/off, unlock); nothing polls.
 */
@SuppressLint("StaticFieldLeak")
object LedController {

    private const val TAG = "GlowLED"
    private const val FSI_CHANNEL = "aod_launcher"
    const val FSI_ID = 4201
    private const val LAUNCH_WINDOW_MS = 4000L

    /** Set when the ROM blocked the lock-screen window once, so Auto goes straight to the overlay. */
    const val PREF_WINDOW_BLOCKED = "aod_window_blocked"

    lateinit var app: Context
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var colors: ColorResolver
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** True while [AodActivity] is on screen. */
    @Volatile
    var aodVisible = false

    /** True while Glow LED's own screen is open; it shows its preview on the camera instead. */
    @Volatile
    var appInForeground = false

    /** Set when an AOD session ended (timeout / user) so we don't relight for the same notifications. */
    private var suppressed = false
    private var ourWakeUntil = 0L
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        prefs = Prefs(app)
        colors = ColorResolver(app, prefs)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        ContextCompat.registerReceiver(app, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // v1.8: one-time switch of the old "cycle colours" default to the split-by-app ring.
        if (!prefs.sp.getBoolean("mig_split_ring", false)) {
            prefs.sp.edit().apply { if (prefs.sp.getString("multi_mode", null) == "cycle") remove("multi_mode") }
                .putBoolean("mig_split_ring", true).apply()
        }
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        // Root managers remember the grant, so re-use it after a restart without prompting.
        if (prefs.sp.getBoolean(Privileged.PREF_ROOT_OK, false)) Privileged.markRootGranted()
    }

    // SharedPreferences keeps only a weak reference, so hold the listener in a field.
    private val prefsChanged = Runnable {
        NotifyListenerService.instance?.rebuild()
        refreshAll()
    }
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.removeCallbacks(prefsChanged)
        main.postDelayed(prefsChanged, 150)
    }

    // ---- listeners (AOD activity / overlay) ------------------------------------------------------

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun notifyListeners() = listeners.forEach { it() }

    fun refreshAll() {
        notifyListeners()
        updateOverlay()
    }

    // ---- events -----------------------------------------------------------------------------------

    fun onNotificationsChanged(newPost: Boolean) = main.post {
        if (newPost) {
            suppressed = false
            DebugLog.add("Notification received")
        }
        notifyListeners()
        if (NotificationStore.isEmpty()) {
            cancelPulse()
        } else if (newPost && !isInteractive() && prefs.aodOnNew) {
            startAod(pulse = false)
        }
        updateOverlay()
        if (newPost && prefs.droplet && isInteractive() && !aodVisible) {
            NotificationStore.apps().firstOrNull()?.let { OverlayHost.droplet(it.color) }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    OverlayHost.hide()
                    when {
                        aodVisible -> Unit
                        !prefs.aodOnScreenOff -> DebugLog.add("Screen off (show-on-screen-off is disabled)")
                        suppressed -> DebugLog.add("Screen off (already shown for these notifications)")
                        else -> {
                            DebugLog.add("Screen off: ${NotificationStore.colors().size} unread")
                            // Give the system a moment to finish its own screen-off transition.
                            main.postDelayed({ if (!isInteractive()) startAod(pulse = false) }, 800)
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (SystemClock.uptimeMillis() > ourWakeUntil && !aodVisible) {
                        // The user woke the phone themselves: next screen-off may show the AOD again.
                        suppressed = false
                        cancelPulse()
                    }
                    updateOverlay()
                }
                Intent.ACTION_USER_PRESENT -> {
                    suppressed = false
                    cancelPulse()
                    if (prefs.clearOnUnlock) {
                        NotificationStore.markAllSeen()
                        notifyListeners()
                    }
                    updateOverlay()
                }
                Intent.ACTION_CONFIGURATION_CHANGED -> updateOverlay()
            }
        }
    }

    // ---- screen-off AOD ---------------------------------------------------------------------------

    fun isInteractive(): Boolean = app.getSystemService(PowerManager::class.java).isInteractive

    fun isLocked(): Boolean = app.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun canTurnScreenOff(): Boolean =
        LedAccessibilityService.instance != null || Privileged.backend() != Privileged.Backend.NONE

    fun canLaunchFromBackground(): Boolean =
        LedAccessibilityService.instance != null || Settings.canDrawOverlays(app)

    fun startAod(pulse: Boolean, preview: Boolean = false) {
        if (!preview) {
            if (!prefs.enabled) return DebugLog.add("Skipped: Glow LED is off")
            if (!prefs.aodEnabled) return DebugLog.add("Skipped: screen-off light is off")
            if (aodVisible || AodOverlay.isShowing) return
            if (NotificationStore.isEmpty()) return DebugLog.add("Skipped: no notifications to show")
            PowerGuard.blockReason(app, prefs)?.let { return DebugLog.add("Skipped: $it") }
        }
        val duration = if (pulse) prefs.pulseDurationSec else prefs.aodTimeoutSec
        val overlayAvailable = LedAccessibilityService.instance != null

        // Two ways to show the black screen:
        //  - lock-screen window (AodActivity): Android waits for its first frame before turning the
        //    panel on, so it comes up already black and dim - no flicker. Some ROMs block it.
        //  - accessibility overlay: can't be blocked, but the panel is woken first, so the lock
        //    screen can flash for a few ms before the overlay draws.
        // Auto prefers the window and falls back to the overlay if the ROM blocks it.
        val useOverlay = overlayAvailable && !preview && when (prefs.aodMethod) {
            "overlay" -> true
            "activity" -> false
            else -> !canLaunchFromBackground() || OemHelper.miuiLockScreenAllowed(app) == false ||
                prefs.sp.getBoolean(PREF_WINDOW_BLOCKED, false)
        }
        if (useOverlay) {
            startOverlay(duration, preview)
            return
        }
        DebugLog.add(
            if (canLaunchFromBackground()) "Light starting (lock-screen window)"
            else "Light starting (full-screen alert) - allow Display over other apps",
        )
        if (!preview && overlayAvailable && prefs.aodMethod == "auto") {
            // Watchdog: if the ROM silently blocked the window, use the overlay and remember it.
            main.postDelayed({
                if (!aodVisible && !AodOverlay.isShowing && !NotificationStore.isEmpty()) {
                    DebugLog.add("Lock-screen window was blocked by the system - switching to overlay")
                    prefs.sp.edit().putBoolean(PREF_WINDOW_BLOCKED, true).apply()
                    startOverlay(duration, false)
                }
            }, 1300)
        }
        val intent = Intent(app, AodActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_USER_ACTION,
            )
            putExtra(AodActivity.EXTRA_DURATION, duration)
            putExtra(AodActivity.EXTRA_PREVIEW, preview)
        }
        ourWakeUntil = SystemClock.uptimeMillis() + LAUNCH_WINDOW_MS
        // Re-check the overlay once the launch window has passed (covers a failed launch).
        main.postDelayed({ updateOverlay() }, LAUNCH_WINDOW_MS + 50)
        var launched = false
        if (preview || canLaunchFromBackground()) {
            try {
                app.startActivity(intent)
                launched = true
            } catch (e: Exception) {
                Log.w(TAG, "Direct AOD launch failed", e)
            }
        }
        if (!launched) launchViaFullScreenIntent(intent)
        // Only force the panel on if turnScreenOn didn't (some OEM ROMs ignore it); doing it
        // first would flash the lock screen before the black AOD appears.
        if (!preview) main.postDelayed({ if (aodVisible && !isInteractive()) wakeBriefly() }, 600)
    }

    /**
     * The lock-screen window didn't cover the status bar (HyperOS keeps it on some builds), so the
     * camera area can't be drawn. Auto mode switches to the overlay, which always covers it.
     */
    fun onAodWindowOffset(px: Int) {
        if (LedAccessibilityService.instance != null && prefs.aodMethod == "auto") {
            DebugLog.add("Status bar stayed visible ($px px) - using the overlay from now on")
            prefs.sp.edit().putBoolean(PREF_WINDOW_BLOCKED, true).apply()
        } else {
            DebugLog.add("Status bar stayed visible ($px px) - enable Accessibility so the light can cover it")
        }
    }

    private fun startOverlay(duration: Int, preview: Boolean) {
        ourWakeUntil = SystemClock.uptimeMillis() + LAUNCH_WINDOW_MS
        if (AodOverlay.show(duration, preview)) {
            // The overlay is added (black, dim) before the panel wakes, keeping any flash minimal.
            if (!preview && !isInteractive()) main.postDelayed({ wakeBriefly() }, 60)
            DebugLog.add("Light started (overlay)")
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeBriefly() {
        // Helps OEM ROMs that ignore turnScreenOn on the activity alone.
        try {
            val pm = app.getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "GlowLED:wake",
            )
            wl.acquire(1500)
            main.postDelayed({ if (!isInteractive()) DebugLog.add("Screen did not turn on - check 'Turn screen on' / lock-screen permissions") }, 1200)
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed", e)
        }
    }

    /** Last-resort launch path when neither accessibility nor "display over apps" is granted. */
    private fun launchViaFullScreenIntent(intent: Intent) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) return
        if (nm.getNotificationChannel(FSI_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(FSI_CHANNEL, app.getString(R.string.channel_launcher), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                },
            )
        }
        val pi = PendingIntent.getActivity(app, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, FSI_CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(app.getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .setTimeoutAfter(5000)
            .setSilent(true)
            .build()
        try {
            nm.notify(FSI_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission", e)
        }
    }

    enum class EndReason { TIMEOUT, USER, CLEARED, POCKET, SCREEN_OFF, UNLOCK }

    fun onAodEnded(reason: EndReason) {
        aodVisible = false
        if (reason != EndReason.CLEARED) suppressed = true
        if ((reason == EndReason.TIMEOUT || reason == EndReason.POCKET) && prefs.pulseIntervalMin > 0) {
            schedulePulse()
        }
        updateOverlay()
    }

    fun onPulseAlarm() {
        if (isInteractive() || NotificationStore.isEmpty()) return
        suppressed = false
        startAod(pulse = true)
    }

    private fun pulseIntent() = PendingIntent.getBroadcast(
        app, 2, Intent(app, PulseReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun schedulePulse() {
        val am = app.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + prefs.pulseIntervalMin * 60_000L
        // Inexact + allow-while-idle: no exact-alarm permission needed and Doze-friendly.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pulseIntent())
    }

    fun cancelPulse() {
        app.getSystemService(AlarmManager::class.java).cancel(pulseIntent())
    }

    /** Locks/turns the screen off (needs the accessibility service). Returns false if unavailable. */
    /** Switches the panel off: accessibility lock action, else Shizuku/root power key. */
    fun turnScreenOff(): Boolean =
        LedAccessibilityService.instance?.lockScreen() == true || Privileged.screenOff()

    // ---- screen-on overlay ------------------------------------------------------------------------

    fun updateOverlay() {
        if (!initialized) return
        val launching = SystemClock.uptimeMillis() < ourWakeUntil
        val want = prefs.enabled && prefs.overlayEnabled && !aodVisible && !launching && !appInForeground && isInteractive() &&
            !NotificationStore.isEmpty() && (!prefs.overlayLockOnly || isLocked()) &&
            (prefs.style.isEdge || CutoutGeometry.isPortrait(app))
        if (want) OverlayHost.show(app) else OverlayHost.hide()
    }
}

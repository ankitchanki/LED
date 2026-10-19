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
import android.os.BatteryManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
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
    private const val MOTION_MIN_GAP_MS = 2 * 60_000L
    private const val CHARGE_FLASH_MS = 4000L
    private const val PROGRESS_RECHECK_MS = 3 * 60_000L + 1000L

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
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(app, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        migrate()
        syncBatteryWatch()
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
        // Root managers remember the grant, so re-use it after a restart without prompting.
        if (prefs.sp.getBoolean(Privileged.PREF_ROOT_OK, false)) Privileged.markRootGranted()
    }

    // SharedPreferences keeps only a weak reference, so hold the listener in a field.
    private val prefsChanged = Runnable {
        NotifyListenerService.instance?.rebuild()
        syncBatteryWatch()
        refreshAll()
    }
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.removeCallbacks(prefsChanged)
        main.postDelayed(prefsChanged, 150)
    }

    /** One-time clean-up of settings from older versions. */
    private fun migrate() {
        val sp = prefs.sp
        val e = sp.edit()
        // v1.8: the old "cycle colours" default becomes the split-by-app ring.
        if (!sp.getBoolean("mig_split_ring", false)) {
            if (sp.getString("multi_mode", null) == "cycle") e.remove("multi_mode")
            e.putBoolean("mig_split_ring", true)
        }
        // v1.8.1: drop keys no version reads any more.
        listOf("tap_dismiss", "double_tap_off").forEach { if (sp.contains(it)) e.remove(it) }
        e.apply()
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
            disarmMotion()
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
                            main.postDelayed({ startAfterScreenOff(retries = 2) }, 800)
                        }
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (SystemClock.uptimeMillis() > ourWakeUntil && !aodVisible) {
                        // The user woke the phone themselves: next screen-off may show the AOD again.
                        suppressed = false
                        cancelPulse()
                        disarmMotion()
                    }
                    updateOverlay()
                }
                Intent.ACTION_USER_PRESENT -> {
                    suppressed = false
                    cancelPulse()
                    disarmMotion()
                    if (prefs.clearOnUnlock) {
                        NotificationStore.markAllSeen()
                        notifyListeners()
                    }
                    updateOverlay()
                }
                Intent.ACTION_CONFIGURATION_CHANGED -> updateOverlay()
                Intent.ACTION_POWER_CONNECTED -> {
                    // Show the charging ring for a moment even over the notification light.
                    chargeFlashUntil = SystemClock.uptimeMillis() + CHARGE_FLASH_MS
                    main.postDelayed({ updateOverlay() }, CHARGE_FLASH_MS + 50)
                    syncBatteryWatch(pluggedHint = true)
                    updateOverlay()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    syncBatteryWatch(pluggedHint = false)
                    updateOverlay()
                }
            }
        }
    }

    /** Some ROMs report the screen as on for a moment after it went off; try again a bit later. */
    private fun startAfterScreenOff(retries: Int) {
        when {
            !isInteractive() -> startAod(pulse = false)
            retries > 0 -> main.postDelayed({ startAfterScreenOff(retries - 1) }, 700)
            else -> DebugLog.add("Skipped: screen came back on")
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
            if (aodVisible || AodOverlay.isShowing) return DebugLog.add("Skipped: light already showing")
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
    /**
     * Returns true when the caller should end its session because the overlay takes over right now
     * (it always covers the camera area). Otherwise the caller keeps going with a shifted layout.
     */
    fun onAodWindowOffset(px: Int, preview: Boolean, durationSec: Int): Boolean {
        if (preview) return false
        val overlay = LedAccessibilityService.instance != null && prefs.aodMethod != "activity"
        return if (overlay) {
            DebugLog.add("Window started $px px below the camera - switching to the overlay now")
            prefs.sp.edit().putBoolean(PREF_WINDOW_BLOCKED, true).apply()
            // Let the activity finish first, then show the overlay for the rest of the session.
            main.postDelayed({ startOverlay(durationSec, false) }, 120)
            true
        } else {
            DebugLog.add("Window started $px px below the camera - turn on Accessibility (or Shizuku setup) so the ring can sit on the camera")
            false
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

    /** The ROM switched the screen off right after the light woke it: turn it back on. */
    fun wakeForLight() {
        ourWakeUntil = SystemClock.uptimeMillis() + LAUNCH_WINDOW_MS
        wakeBriefly()
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

    /** SWITCH = handing over to another host (activity → overlay); not a user dismissal. */
    enum class EndReason { TIMEOUT, USER, CLEARED, POCKET, SCREEN_OFF, UNLOCK, SWITCH }

    fun onAodEnded(reason: EndReason) {
        aodVisible = false
        if (reason != EndReason.CLEARED && reason != EndReason.SWITCH) suppressed = true
        if ((reason == EndReason.TIMEOUT || reason == EndReason.POCKET) && prefs.pulseIntervalMin > 0) {
            schedulePulse()
        }
        if (reason == EndReason.TIMEOUT && !NotificationStore.isEmpty()) armMotion()
        updateOverlay()
    }

    // ---- show again when the phone moves --------------------------------------------------------
    // The significant-motion sensor is a one-shot wake-up trigger handled by the sensor hub: while
    // armed it costs nothing (the CPU can sleep), unlike reminder pulses that light the panel blindly.

    private var motionArmed = false
    private var lastMotionGlance = 0L

    private val motionTrigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            main.post {
                motionArmed = false
                onPhoneMoved()
            }
        }
    }

    private fun motionSensor(): Pair<SensorManager, Sensor>? {
        val sm = app.getSystemService(SensorManager::class.java) ?: return null
        val s = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return null
        return sm to s
    }

    private fun armMotion() {
        if (motionArmed || !prefs.motionGlance) return
        val (sm, s) = motionSensor() ?: return
        motionArmed = sm.requestTriggerSensor(motionTrigger, s)
        if (motionArmed) DebugLog.add("Will light up again when the phone moves")
    }

    private fun disarmMotion() {
        if (!motionArmed) return
        motionArmed = false
        motionSensor()?.let { (sm, s) -> sm.cancelTriggerSensor(motionTrigger, s) }
    }

    private fun onPhoneMoved() {
        if (isInteractive() || aodVisible || NotificationStore.isEmpty() || !prefs.motionGlance) return
        val since = SystemClock.uptimeMillis() - lastMotionGlance
        if (since < MOTION_MIN_GAP_MS) {
            // Walking around: don't keep lighting up, check again a bit later.
            main.postDelayed({ if (!isInteractive() && !aodVisible) armMotion() }, MOTION_MIN_GAP_MS - since)
            return
        }
        lastMotionGlance = SystemClock.uptimeMillis()
        DebugLog.add("Phone moved: showing the light again")
        suppressed = false
        startAod(pulse = true)
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
        val canShow = prefs.enabled && !aodVisible && !launching && !appInForeground && isInteractive() &&
            (prefs.style.isEdge || CutoutGeometry.isPortrait(app))
        val notifRing = prefs.overlayEnabled && !NotificationStore.isEmpty() && (!prefs.overlayLockOnly || isLocked())
        overlayMeter = if (canShow) computeMeter(notifRing) else null
        if (canShow && (notifRing || overlayMeter != null)) OverlayHost.show(app) else OverlayHost.hide()
    }

    // ---- charging ring and download ring (screen on) ----------------------------------------------

    /** A gauge the screen-on ring shows instead of the notification light. */
    data class Meter(val fraction: Float, val color: Int)

    /** What the screen-on ring currently shows as a gauge (null = the notification light). */
    var overlayMeter: Meter? = null
        private set

    private var plugged = false
    private var batteryPct = 0
    private var chargeFlashUntil = 0L
    private var batteryWatching = false

    /** Download progress first (it's short-lived), then charging when no notification is waiting. */
    private fun computeMeter(notifRing: Boolean): Meter? {
        if (prefs.progressLed) ProgressStore.current()?.let {
            return Meter(it.fraction, if (prefs.progressColor != 0) prefs.progressColor else it.color)
        }
        if (prefs.chargeLed && plugged && (!notifRing || SystemClock.uptimeMillis() < chargeFlashUntil)) {
            return Meter(batteryPct / 100f, prefs.chargeColor(batteryPct))
        }
        return null
    }

    fun onProgressChanged() = main.post {
        updateOverlay()
        // A transfer that stalls stops showing after a while; re-check then.
        main.removeCallbacks(progressRecheck)
        main.postDelayed(progressRecheck, PROGRESS_RECHECK_MS)
    }

    private val progressRecheck = Runnable { updateOverlay() }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onBatteryChanged(i)
    }

    /**
     * Listens to battery-level changes only while plugged in and the charging ring is on, so the
     * frequent battery broadcasts never wake Glow LED on battery power.
     */
    private fun syncBatteryWatch(pluggedHint: Boolean? = null) {
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val isPlugged = pluggedHint ?: ((sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
        plugged = isPlugged
        sticky?.let { batteryPct = levelOf(it) ?: batteryPct }
        val want = isPlugged && prefs.enabled && prefs.chargeLed
        if (want && !batteryWatching) {
            ContextCompat.registerReceiver(
                app, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )?.let { onBatteryChanged(it) }
            batteryWatching = true
        } else if (!want && batteryWatching) {
            try {
                app.unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {
            }
            batteryWatching = false
        }
    }

    private fun levelOf(i: Intent): Int? {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)
    }

    private fun onBatteryChanged(i: Intent) {
        val p = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val pct = levelOf(i) ?: batteryPct
        if (p == plugged && pct == batteryPct) return
        plugged = p
        batteryPct = pct
        if (!p) syncBatteryWatch(pluggedHint = false)
        updateOverlay()
    }
}

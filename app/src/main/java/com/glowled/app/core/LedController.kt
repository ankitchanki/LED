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
import com.glowled.app.service.LedAccessibilityService
import com.glowled.app.service.NotifyListenerService
import com.glowled.app.service.OverlayHost
import com.glowled.app.service.PulseReceiver
import com.glowled.app.ui.AodActivity
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
        prefs.sp.registerOnSharedPreferenceChangeListener(prefListener)
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
        if (newPost) suppressed = false
        notifyListeners()
        if (NotificationStore.isEmpty()) {
            cancelPulse()
        } else if (newPost && !isInteractive() && prefs.aodOnNew) {
            startAod(pulse = false)
        }
        updateOverlay()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    OverlayHost.hide()
                    if (!aodVisible && !suppressed && prefs.aodOnScreenOff) {
                        // Give the system a moment to finish its own screen-off transition.
                        main.postDelayed({ if (!isInteractive()) startAod(pulse = false) }, 700)
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

    fun canLaunchFromBackground(): Boolean =
        LedAccessibilityService.instance != null || Settings.canDrawOverlays(app)

    fun startAod(pulse: Boolean, preview: Boolean = false) {
        if (!preview) {
            if (!prefs.enabled || !prefs.aodEnabled || aodVisible) return
            if (NotificationStore.isEmpty()) return
            PowerGuard.blockReason(app, prefs)?.let { Log.d(TAG, "AOD blocked: $it"); return }
        }
        val duration = if (pulse) prefs.pulseDurationSec else prefs.aodTimeoutSec
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
        if (!preview) main.postDelayed({ if (!isInteractive()) wakeBriefly() }, 450)
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
    fun turnScreenOff(): Boolean = LedAccessibilityService.instance?.lockScreen() == true

    // ---- screen-on overlay ------------------------------------------------------------------------

    fun updateOverlay() {
        if (!initialized) return
        val launching = SystemClock.uptimeMillis() < ourWakeUntil
        val want = prefs.enabled && prefs.overlayEnabled && !aodVisible && !launching && isInteractive() &&
            !NotificationStore.isEmpty() && (!prefs.overlayLockOnly || isLocked()) &&
            (prefs.style.isEdge || CutoutGeometry.isPortrait(app))
        if (want) OverlayHost.show(app) else OverlayHost.hide()
    }
}

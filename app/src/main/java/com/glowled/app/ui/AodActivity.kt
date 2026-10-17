package com.glowled.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.glowled.app.core.CutoutGeometry
import com.glowled.app.core.LedController
import com.glowled.app.core.LedController.EndReason
import com.glowled.app.core.NotificationStore
import com.glowled.app.view.LedView
import kotlin.random.Random

/**
 * Black always-on screen shown while the phone is "off". On AMOLED, black pixels are unlit,
 * so only the LED itself draws power. Brightness and refresh rate are forced to their minimum.
 */
class AodActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var led: LedView
    private var preview = false
    private var ended = false
    private var proximityLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var nearSince = 0L
    private var resumedAt = 0L

    private val storeListener: () -> Unit = { runOnUiThread { onStoreChanged() } }

    private val timeout = Runnable { end(EndReason.TIMEOUT, lock = true) }

    private val burnIn = object : Runnable {
        override fun run() {
            led.shiftX = Random.nextInt(-3, 4).toFloat()
            led.shiftY = Random.nextInt(-3, 4).toFloat()
            led.invalidate()
            handler.postDelayed(this, BURN_IN_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                // Power button pressed while showing: the user wants the screen off.
                Intent.ACTION_SCREEN_OFF -> end(EndReason.SCREEN_OFF, lock = false)
                Intent.ACTION_USER_PRESENT -> end(EndReason.UNLOCK, lock = false)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LedController.init(this)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        getSystemService(NotificationManager::class.java).cancel(LedController.FSI_ID)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        configureWindow()

        val prefs = LedController.prefs
        led = LedView(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(led, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)
        hideSystemBars(root)
        led.configure(prefs, CutoutGeometry.resolve(this, prefs), currentColors())

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (preview || LedController.prefs.tapToDismiss) end(EndReason.USER, lock = false)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (LedController.prefs.doubleTapOff) end(EndReason.USER, lock = true)
                return true
            }
        })
        root.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }

        LedController.aodVisible = true
        LedController.addListener(storeListener)
        LedController.updateOverlay()
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val seconds = intent.getIntExtra(EXTRA_DURATION, prefs.aodTimeoutSec)
        if (seconds > 0) handler.postDelayed(timeout, seconds * 1000L)
        if (preview) handler.postDelayed(timeout, PREVIEW_MS)
        if (prefs.burnIn && !preview) handler.postDelayed(burnIn, BURN_IN_MS)
        if (prefs.proximity && !preview) startProximity()
    }

    private fun configureWindow() {
        val prefs = LedController.prefs
        val lp = window.attributes
        lp.screenBrightness = (prefs.aodBrightness / 100f).coerceIn(0.01f, 1f)
        lp.buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (prefs.lowRefresh) {
            // 120 Hz panels burn noticeably more power; ask for the slowest mode at the same resolution.
            val d = CutoutGeometry.display(this)
            val cur = d.mode
            d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .minByOrNull { it.refreshRate }
                ?.let {
                    lp.preferredDisplayModeId = it.modeId
                    lp.preferredRefreshRate = it.refreshRate
                }
        }
        window.attributes = lp
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars(root: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            root.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    private fun currentColors(): IntArray {
        if (preview) {
            val c = NotificationStore.colors()
            return if (c.isNotEmpty()) c else intArrayOf(LedController.prefs.defaultColor, 0xFF25D366.toInt(), 0xFFFF3B6B.toInt())
        }
        return NotificationStore.colors()
    }

    private fun onStoreChanged() {
        if (ended) return
        if (!preview && (NotificationStore.isEmpty() || !LedController.prefs.enabled || !LedController.prefs.aodEnabled)) {
            end(EndReason.CLEARED, lock = true)
            return
        }
        led.configure(LedController.prefs, CutoutGeometry.resolve(this, LedController.prefs), currentColors())
    }

    // ---- pocket / face-down detection ---------------------------------------------------------

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val near = e.values[0] < e.sensor.maximumRange.coerceAtMost(5f)
            val now = System.currentTimeMillis()
            if (near) {
                if (nearSince == 0L) nearSince = now
                led.visibility = View.INVISIBLE // nothing to draw while covered
                handler.removeCallbacks(pocketEnd)
                handler.postDelayed(pocketEnd, POCKET_MS)
            } else {
                nearSince = 0L
                handler.removeCallbacks(pocketEnd)
                led.visibility = View.VISIBLE
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val pocketEnd = Runnable { end(EndReason.POCKET, lock = true) }

    private fun startProximity() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        sensorManager = sm
        sm.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        // Lets the hardware switch the panel off while covered, where supported.
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "GlowLED:proximity").apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
        }
    }

    // ---- teardown --------------------------------------------------------------------------------

    private fun end(reason: EndReason, lock: Boolean) {
        if (ended) return
        ended = true
        cleanup()
        notifyEnded(reason)
        if (lock && !preview) {
            if (!LedController.turnScreenOff()) {
                // Without accessibility we can't switch the panel off: go fully dark and let the
                // system screen timeout do it. The activity finishes on ACTION_SCREEN_OFF.
                led.visibility = View.GONE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = window.attributes
                lp.screenBrightness = 0.01f
                window.attributes = lp
                ContextCompat.registerReceiver(
                    this, finishOnOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                registeredFinish = true
                return
            }
        }
        finish()
    }

    private fun notifyEnded(reason: EndReason) {
        if (preview) {
            LedController.aodVisible = false
            LedController.updateOverlay()
        } else {
            LedController.onAodEnded(reason)
        }
    }

    private var registeredFinish = false
    private val finishOnOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        LedController.removeListener(storeListener)
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        sensorManager?.unregisterListener(proximityListener)
        sensorManager = null
        proximityLock?.let { if (it.isHeld) it.release() }
        proximityLock = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (ended) {
            // A new notification arrived while we were fading out: start a fresh session.
            recreate()
        } else {
            led.restartAnimation()
        }
    }

    override fun onResume() {
        super.onResume()
        if (resumedAt == 0L) resumedAt = System.currentTimeMillis()
    }

    override fun onStop() {
        super.onStop()
        // Another activity (e.g. an incoming call) took over: end the session. Ignore the brief
        // stop some ROMs send while the screen is still turning on.
        val settled = resumedAt != 0L && System.currentTimeMillis() - resumedAt > 1500
        if (!ended && settled && !isChangingConfigurations) {
            end(EndReason.USER, lock = false)
            if (!isFinishing) finish()
        }
    }

    override fun onDestroy() {
        if (!ended) {
            ended = true
            cleanup()
            notifyEnded(EndReason.USER)
        }
        if (registeredFinish) {
            try {
                unregisterReceiver(finishOnOff)
            } catch (_: Exception) {
            }
        }
        LedController.aodVisible = false
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        end(EndReason.USER, lock = false)
    }

    companion object {
        const val EXTRA_DURATION = "duration"
        const val EXTRA_PREVIEW = "preview"
        private const val BURN_IN_MS = 60_000L
        private const val POCKET_MS = 10_000L
        private const val PREVIEW_MS = 15_000L
    }
}

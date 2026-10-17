package com.glowled.app.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.glowled.app.core.CutoutGeometry
import com.glowled.app.core.DebugLog
import com.glowled.app.core.LedController
import com.glowled.app.core.LedController.EndReason
import com.glowled.app.core.NotificationStore
import com.glowled.app.view.LedView
import kotlin.random.Random

/**
 * One always-on session: black screen + LED, timeout, pocket detection, burn-in shift and gestures.
 * Hosted either in an accessibility overlay window ([com.glowled.app.service.AodOverlay], works on
 * ROMs that block background activity starts such as HyperOS) or in [AodActivity].
 */
class AodSession(
    private val context: Context,
    private val preview: Boolean,
    durationSec: Int,
    private val host: Host,
) {
    interface Host {
        /** Removes the window / finishes the activity. [lock] = also switch the screen off. */
        fun close(lock: Boolean)

        /** Called when the screen must be allowed to time out on its own. */
        fun releaseKeepScreenOn()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = LedController.prefs
    val led = LedView(context)
    val root: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(led, FrameLayout.LayoutParams(-1, -1))
    }
    var ended = false
        private set
    private var proximityLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var receiverRegistered = false

    private val storeListener: () -> Unit = { handler.post { onStoreChanged() } }
    private val timeout = Runnable { end(EndReason.TIMEOUT, lock = true) }
    private val pocketEnd = Runnable { end(EndReason.POCKET, lock = true) }
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
                // Power button while showing: the user wants the screen off.
                Intent.ACTION_SCREEN_OFF -> end(EndReason.SCREEN_OFF, lock = false)
                Intent.ACTION_USER_PRESENT -> end(EndReason.UNLOCK, lock = false)
            }
        }
    }

    init {
        led.useScreenCoords = true
        led.configure(prefs, CutoutGeometry.resolve(context, prefs), currentColors())
        installGestures()
        if (durationSec > 0) handler.postDelayed(timeout, durationSec * 1000L)
        if (preview) handler.postDelayed(timeout, PREVIEW_MS)
    }

    /** Call once the host window is visible. */
    fun start() {
        LedController.aodVisible = true
        LedController.addListener(storeListener)
        LedController.updateOverlay()
        ContextCompat.registerReceiver(
            context, receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        if (prefs.burnIn && !preview) handler.postDelayed(burnIn, BURN_IN_MS)
        if (prefs.proximity && !preview) startProximity()
        DebugLog.add(if (preview) "Preview shown" else "Always-on light shown")
    }

    fun restartAnimation() = led.restartAnimation()

    @SuppressLint("ClickableViewAccessibility")
    private fun installGestures() {
        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (preview || prefs.tapToDismiss) end(EndReason.USER, lock = false)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (preview) end(EndReason.USER, lock = false)
                else if (prefs.doubleTapOff) end(EndReason.USER, lock = true)
                return true
            }
        })
        root.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
    }

    private fun currentColors(): IntArray {
        val c = NotificationStore.colors()
        if (preview && c.isEmpty()) return intArrayOf(prefs.defaultColor, 0xFF25D366.toInt(), 0xFFFF4081.toInt())
        return c
    }

    private fun onStoreChanged() {
        if (ended) return
        if (!preview && (NotificationStore.isEmpty() || !prefs.enabled || !prefs.aodEnabled)) {
            end(EndReason.CLEARED, lock = true)
            return
        }
        led.configure(prefs, CutoutGeometry.resolve(context, prefs), currentColors())
    }

    // ---- pocket / face-down detection -----------------------------------------------------------

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val near = e.values[0] < e.sensor.maximumRange.coerceAtMost(5f)
            if (near) {
                led.visibility = View.INVISIBLE // nothing to draw while covered
                handler.removeCallbacks(pocketEnd)
                handler.postDelayed(pocketEnd, POCKET_MS)
            } else {
                handler.removeCallbacks(pocketEnd)
                led.visibility = View.VISIBLE
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun startProximity() {
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        sensorManager = sm
        sm.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        // Lets the hardware switch the panel off while covered, where supported.
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "GlowLED:proximity").apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
        }
    }

    // ---- teardown -------------------------------------------------------------------------------

    fun end(reason: EndReason, lock: Boolean) {
        if (ended) return
        ended = true
        DebugLog.add("Light ended: ${reason.name.lowercase()}")
        cleanup()
        if (preview) {
            LedController.aodVisible = false
            LedController.updateOverlay()
        } else {
            LedController.onAodEnded(reason)
        }
        if (lock && !preview && !LedController.canTurnScreenOff()) {
            // Can't switch the panel off without accessibility: go dark and let the normal
            // screen timeout do it.
            led.visibility = View.GONE
            host.releaseKeepScreenOn()
            return
        }
        host.close(lock && !preview)
    }

    /** Called by the host when it is destroyed without [end] (e.g. killed by the system). */
    fun onHostGone() {
        if (!ended) {
            ended = true
            cleanup()
            if (preview) LedController.updateOverlay() else LedController.onAodEnded(EndReason.USER)
        }
        LedController.aodVisible = false
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        LedController.removeListener(storeListener)
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        sensorManager?.unregisterListener(proximityListener)
        sensorManager = null
        proximityLock?.let { if (it.isHeld) it.release() }
        proximityLock = null
    }

    companion object {
        private const val BURN_IN_MS = 60_000L
        private const val POCKET_MS = 10_000L
        private const val PREVIEW_MS = 15_000L

        /** Slowest display mode at the current resolution (big saving on 90/120 Hz panels). */
        fun lowestModeId(context: Context): Pair<Int, Float>? {
            val d = CutoutGeometry.display(context)
            val cur = d.mode
            return d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .minByOrNull { it.refreshRate }
                ?.let { it.modeId to it.refreshRate }
        }
    }
}

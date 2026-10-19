package com.glowled.app.ui

import android.animation.ValueAnimator
import androidx.core.animation.doOnEnd
import androidx.core.view.doOnLayout
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
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.glowled.app.core.CutoutGeometry
import com.glowled.app.core.DebugLog
import com.glowled.app.core.LedController
import com.glowled.app.core.LedController.EndReason
import com.glowled.app.core.NotificationStore
import com.glowled.app.view.AppInfoView
import com.glowled.app.view.LedView
import com.glowled.app.view.NotificationPeekView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One always-on session: black screen + LED, timeout, pocket detection, burn-in shift and gestures.
 * Hosted either in an accessibility overlay window ([com.glowled.app.service.AodOverlay], works on
 * ROMs that block background activity starts such as HyperOS) or in [AodActivity].
 */
class AodSession(
    private val context: Context,
    private val preview: Boolean,
    private val durationSec: Int,
    private val host: Host,
) {
    interface Host {
        /** Removes the window / finishes the activity. [lock] = also switch the screen off. */
        fun close(lock: Boolean)

        /** Called when the screen must be allowed to time out on its own. */
        fun releaseKeepScreenOn()

        /** Asks to unlock (when possible) and then runs [then]; default: run right away. */
        fun dismissKeyguard(then: () -> Unit) = then()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = LedController.prefs
    val led = LedView(context)
    /** Lays the app strip and peek card out under the camera as part of its own layout pass. */
    val root: FrameLayout = object : FrameLayout(context) {
        private var drawn = false

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            positionInfo()
        }

        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            if (!drawn) {
                drawn = true
                DebugLog.add("Light window drew its first frame, ${LedController.state()}")
                onFirstDraw?.invoke()
            }
        }
    }.apply {
        setBackgroundColor(Color.BLACK)
        addView(led, FrameLayout.LayoutParams(-1, -1))
    }
    var ended = false
        private set

    /** Called once, when the black window has drawn its first frame. */
    var onFirstDraw: (() -> Unit)? = null
    private var sensorManager: SensorManager? = null
    private var receiverRegistered = false

    private val storeListener: () -> Unit = { handler.post { onStoreChanged() } }
    private val timeout = Runnable { end(EndReason.TIMEOUT, lock = true) }
    private val pocketEnd = Runnable { end(EndReason.POCKET, lock = true) }
    /** Covered for a moment longer than a false reading: hide the light, end after [POCKET_MS]. */
    private val coverConfirmed = Runnable {
        DebugLog.add("Covered (pocket detection): light hidden")
        led.visibility = View.INVISIBLE // nothing to draw while covered
        info.visibility = View.INVISIBLE
        peek.visibility = View.INVISIBLE
        handler.removeCallbacks(pocketEnd)
        handler.postDelayed(pocketEnd, POCKET_MS)
    }
    /** When the panel last came on, to tell the system switching it off from the user doing it. */
    private var panelOnAt = 0L
    private var rewoken = false
    /** Eco: once the entrance and droplet have played, hold a still ring (no more redraws). */
    private val settle = Runnable { led.settle() }
    /** Re-dims the ring as the notifications get older. */
    private val ageTick = object : Runnable {
        override fun run() {
            updateAgeFade()
            handler.postDelayed(this, AGE_TICK_MS)
        }
    }
    private val burnIn = object : Runnable {
        override fun run() {
            led.shiftX = Random.nextInt(-3, 4).toFloat()
            led.shiftY = Random.nextInt(-3, 4).toFloat()
            led.invalidate()
            root.requestLayout() // re-place the strip with the new shift
            handler.postDelayed(this, BURN_IN_MS)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                // Power button while showing: the user wants the screen off.
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_USER_PRESENT -> end(EndReason.UNLOCK, lock = false)
                // Panel just came on: now the entrance is actually visible.
                Intent.ACTION_SCREEN_ON -> {
                    DebugLog.add("Light: screen came on")
                    panelOnAt = android.os.SystemClock.uptimeMillis()
                    playEntrance()
                }
            }
        }
    }

    // ---- app strip + peek card under the camera (One UI style) -----------------------------------

    private val density = context.resources.displayMetrics.density
    private val info = AppInfoView(context)
    private val peek = NotificationPeekView(context)
    private val hideInfo = Runnable { info.hide(); hidePeek() }
    private val hidePeekRunnable = Runnable { hidePeek() }
    private var entrancePlayed = false
    private var intro: ValueAnimator? = null
    private var newestTime = NotificationStore.newestTime()
    private var offsetReported = false

    /**
     * Ring geometry for this window. Normally the real camera position; if the window starts below
     * the camera (some HyperOS lock screens) the ring is moved just under the window's top edge so
     * it stays visible instead of being drawn off-window.
     */
    private var yShift = 0f

    private fun geometry() = CutoutGeometry.resolve(context, prefs).let { if (yShift > 0f) it.copy(cy = it.cy + yShift) else it }

    /** Height of the status bar / camera cutout band inside this window (from the window insets). */
    private fun statusBandPx(): Int {
        val ins = root.rootWindowInsets ?: return 0
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            ins.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout(),
            ).top
        } else {
            @Suppress("DEPRECATION")
            max(ins.displayCutout?.safeInsetTop ?: 0, ins.stableInsetTop)
        }
    }

    /**
     * Centres the strip (and the peek card under it) below the ring, in this window's coordinates.
     * Both always start below the status bar / camera area, so text is never drawn over the camera.
     */
    private fun positionInfo() {
        if (!root.isAttachedToWindow) return
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        if (loc[1] > 0 && !offsetReported) {
            offsetReported = true
            // Only the lock-screen window hands over to the overlay (never the overlay to itself).
            if (context is android.app.Activity && LedController.onAodWindowOffset(loc[1], preview, remainingSec())) {
                // Not inside the layout pass: hand over right after it.
                root.post { end(EndReason.SWITCH, lock = false) }
                return
            }
            // No overlay available: keep the ring visible right under the window's top edge.
            val g = CutoutGeometry.resolve(context, prefs)
            val ringTop = g.cy - led.ringBottom() + g.cy // mirror of the bottom around the centre
            if (ringTop < loc[1]) {
                yShift = loc[1] - ringTop + 6 * density
                led.configure(prefs, geometry(), currentColors())
            }
        }
        val g = geometry()
        // Window coordinates: below the ring, below the status-bar/cutout band, never at the very edge.
        val safeTop = max(led.ringBottom() + 16 * density - loc[1], statusBandPx() + 8 * density)
        infoX = g.cx - loc[0] - info.measuredWidth / 2f + led.shiftX
        infoY = max(safeTop, 8 * density) + led.shiftY
        place(info, infoX, infoY)
        val peekY = infoY + (if (info.visibility == View.VISIBLE) info.measuredHeight + 14 * density else 0f)
        place(peek, g.cx - loc[0] - peek.measuredWidth / 2f, peekY)
    }

    /** Where the strip sits (window coordinates); the droplet aims here. */
    private var infoX = 0f
    private var infoY = 0f

    /**
     * Places a child directly inside the root's layout pass. Not setX/setY: those write
     * translationX/Y, which the entrance/exit animations also animate - they would slide the view
     * back onto the camera. Not layout margins either: changing them during layout only applies on
     * a later pass that isn't guaranteed.
     */
    private fun place(v: View, x: Float, y: Float) {
        val l = x.roundToInt()
        val t = y.roundToInt()
        v.layout(l, t, l + v.measuredWidth, t + v.measuredHeight)
    }

    private val startedAt = android.os.SystemClock.uptimeMillis()

    private fun remainingSec(): Int {
        if (durationSec <= 0) return 0
        val left = durationSec - ((android.os.SystemClock.uptimeMillis() - startedAt) / 1000).toInt()
        return left.coerceAtLeast(10)
    }

    private fun bindInfo() {
        if (!prefs.showAppInfo) return
        info.monochrome = prefs.aodIcons
        var apps = NotificationStore.apps()
        if (preview && apps.isEmpty()) apps = sampleApps
        info.bind(apps)
    }

    /** Shows the app strip, auto-hiding it after a few seconds unless "keep visible" is on. */
    private fun showInfo() {
        if (!prefs.showAppInfo) return
        handler.removeCallbacks(hideInfo)
        info.show()
        if (!prefs.appInfoAlways) handler.postDelayed(hideInfo, INFO_MS)
    }

    /**
     * Liquid drop from the camera into the icon row, then the icons pop in where it lands.
     * Falls back to just showing the icons when the droplet is off.
     */
    private fun dropIntoIcons(color: Int) {
        if (!prefs.droplet || !prefs.showAppInfo) {
            showInfo()
            return
        }
        // Wait for the freshly bound strip to be laid out, so the newest icon's position is known.
        info.doOnLayout {
            if (ended) return@doOnLayout
            handler.removeCallbacks(hideInfo)
            info.prepareDrop()
            val tx = infoX + info.newestCenterX()
            val ty = infoY + info.iconCenterY()
            led.playDroplet(color, ty, targetX = tx) {
                // The drop becomes the app icon.
                info.popNewest()
                if (!prefs.appInfoAlways) handler.postDelayed(hideInfo, INFO_MS)
            }
        }
    }

    /** Ring grows out of the camera and fades in, then a droplet falls and the app strip appears. */
    private fun playEntrance() {
        if (entrancePlayed || ended) return
        entrancePlayed = true
        intro?.cancel()
        intro = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { led.intro = it.animatedValue as Float }
            // Chain the drop on the ring's entrance (frame-driven, no timer to drift or be dropped).
            doOnEnd {
                if (!ended) dropIntoIcons(NotificationStore.apps().firstOrNull()?.color ?: prefs.defaultColor)
                armSettle()
            }
            start()
        }
    }

    // ---- tap an icon: who sent what -------------------------------------------------------------

    private fun onAppTapped(pkg: String) {
        handler.removeCallbacks(hideInfo)
        if (!prefs.showContent) {
            showInfo()
            return
        }
        val entries = NotificationStore.entriesFor(pkg)
        if (entries.isEmpty() || (peek.isShowing() && peekPkg == pkg)) {
            hidePeek()
            showInfo()
            return
        }
        peekPkg = pkg
        peek.actionsEnabled = prefs.peekActions
        peek.bind(entries)
        peek.show()
        handler.removeCallbacks(hidePeekRunnable)
        handler.postDelayed(hidePeekRunnable, PEEK_MS)
    }

    private var peekPkg: String? = null

    private fun hidePeek() {
        peekPkg = null
        peek.hide()
        info.clearSelection()
        if (!prefs.appInfoAlways) {
            handler.removeCallbacks(hideInfo)
            handler.postDelayed(hideInfo, INFO_MS)
        }
    }

    /** Opens the chat behind a notification; Android asks to unlock first when the phone is locked. */
    private fun openNotification(e: NotificationStore.Entry) {
        val pi = e.intent ?: return
        root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        host.dismissKeyguard {
            try {
                val opts = android.app.ActivityOptions.makeBasic()
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    opts.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                }
                pi.send(context, 0, null, null, null, null, opts.toBundle())
            } catch (ex: Exception) {
                DebugLog.add("Could not open notification: ${ex.javaClass.simpleName}")
            }
            // End only after unlocking succeeded, so the PIN prompt isn't cancelled by finishing.
            end(EndReason.USER, lock = false)
        }
    }

    /** Reply sheet over the lock screen (it asks to unlock), using the app's own reply action. */
    private fun replyTo(e: NotificationStore.Entry) {
        root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        if (ReplyActivity.start(context, e.key)) end(EndReason.USER, lock = false)
    }

    /** The app's own "Mark as read"; without one, the notification is dismissed. */
    private fun markRead(e: NotificationStore.Entry) {
        root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        val action = e.readAction
        if (action != null) {
            try {
                action.actionIntent.send()
            } catch (ex: Exception) {
                DebugLog.add("Mark as read failed: ${ex.javaClass.simpleName}")
            }
            // Some apps leave the notification up for a moment: treat it as read right away.
            NotificationStore.markSeen(e.key)
            LedController.refreshAll()
        } else {
            com.glowled.app.service.NotifyListenerService.instance?.cancelNotification(e.key)
        }
        DebugLog.add("Marked as read from the always-on screen")
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
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        if (prefs.burnIn && !preview) handler.postDelayed(burnIn, BURN_IN_MS)
        if (prefs.proximity && !preview) startProximity()
        if (prefs.ageFade && !preview) handler.postDelayed(ageTick, AGE_TICK_MS)
        // If the panel is already on (preview, or the activity woke it) animate now; otherwise
        // wait for ACTION_SCREEN_ON so the motion isn't wasted on a dark panel. Fallback after 1.5 s.
        if (context.getSystemService(PowerManager::class.java).isInteractive) {
            panelOnAt = android.os.SystemClock.uptimeMillis()
            root.post { playEntrance() }
        } else {
            handler.postDelayed({ playEntrance() }, 1500)
        }
        DebugLog.add((if (preview) "Preview shown" else "Always-on light shown") + ", ${LedController.state()}")
    }

    /**
     * The panel went off. Normally that's the power button: end. But if it happens a moment after
     * the panel came on, nobody could have pressed it that fast: the ROM switched the screen off
     * on its own (some do right after a notification wake). Wake it again, once.
     */
    private fun onScreenOff() {
        val sinceOn = android.os.SystemClock.uptimeMillis() - panelOnAt
        DebugLog.add("Light: screen went off" + if (panelOnAt > 0) " ${sinceOn} ms after it came on" else "")
        if (!preview && !rewoken && panelOnAt > 0 && sinceOn < EARLY_OFF_MS) {
            rewoken = true
            DebugLog.add("System switched the screen off ${sinceOn} ms after it woke - waking it again")
            LedController.wakeForLight()
            return
        }
        end(EndReason.SCREEN_OFF, lock = false)
    }

    fun restartAnimation() {
        led.configure(prefs, geometry(), currentColors()) // undoes an eco still ring
        updateAgeFade()
        led.restartAnimation()
        bindInfo()
        showInfo()
        armSettle()
    }

    private fun armSettle() {
        handler.removeCallbacks(settle)
        if (prefs.ecoAnim && !preview) handler.postDelayed(settle, ECO_MS)
    }

    /** Full brightness for a new notification, down to [AGE_MIN] after it has waited an hour. */
    private fun updateAgeFade() {
        val newest = NotificationStore.newestTime()
        led.ageFade = if (!prefs.ageFade || preview || newest <= 0L) 1f else {
            val age = (System.currentTimeMillis() - newest).coerceAtLeast(0L)
            1f - (1f - AGE_MIN) * (age / AGE_FULL_MS.toFloat()).coerceAtMost(1f)
        }
    }

    // ---- gestures: own double-tap detection (more reliable on a dim, half-awake panel) -----------

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private val singleTap = Runnable {
        // Tap on the black area: close the peek card, or bring the app icons back.
        if (peek.isShowing()) hidePeek() else showInfo()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installGestures() {
        val slop = 48 * context.resources.displayMetrics.density
        root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = ev.eventTime; downX = ev.rawX; downY = ev.rawY
                }
                MotionEvent.ACTION_UP -> {
                    val isTap = ev.eventTime - downTime < TAP_MAX_MS &&
                        abs(ev.rawX - downX) < slop && abs(ev.rawY - downY) < slop
                    if (isTap) {
                        val isDouble = ev.eventTime - lastTapTime < DOUBLE_TAP_MS &&
                            abs(ev.rawX - lastTapX) < slop * 2 && abs(ev.rawY - lastTapY) < slop * 2
                        if (isDouble) {
                            handler.removeCallbacks(singleTap)
                            lastTapTime = 0L
                            onDoubleTap()
                        } else {
                            lastTapTime = ev.eventTime; lastTapX = ev.rawX; lastTapY = ev.rawY
                            handler.removeCallbacks(singleTap)
                            handler.postDelayed(singleTap, DOUBLE_TAP_MS)
                        }
                    }
                }
            }
            true
        }
    }

    /** Double tap wakes the phone: drop the black screen and reveal the lock screen. */
    private fun onDoubleTap() {
        if (preview || prefs.doubleTapWake) {
            root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            end(EndReason.USER, lock = false)
        }
    }

    private fun currentColors(): IntArray {
        val c = NotificationStore.colors()
        if (preview && c.isEmpty()) return sampleApps.map { it.color }.distinct().toIntArray()
        return c
    }

    /**
     * Preview without real notifications: messaging apps that are actually installed, so the
     * preview looks like the real thing (instead of Glow LED's own icon).
     */
    private val sampleApps: List<NotificationStore.AppInfo> by lazy { sampleApps(context) }

    private fun onStoreChanged() {
        if (ended) return
        if (!preview && (NotificationStore.isEmpty() || !prefs.enabled || !prefs.aodEnabled)) {
            end(EndReason.CLEARED, lock = true)
            return
        }
        led.configure(prefs, geometry(), currentColors())
        updateAgeFade()
        armSettle()
        bindInfo()
        val newest = NotificationStore.newestTime()
        if (newest > newestTime) {
            // A new notification: drip it out of the camera into the icon row.
            newestTime = newest
            dropIntoIcons(NotificationStore.apps().firstOrNull()?.color ?: prefs.defaultColor)
        } else {
            showInfo()
        }
        peekPkg?.let { pkg ->
            val entries = NotificationStore.entriesFor(pkg)
            if (entries.isEmpty()) hidePeek() else peek.bind(entries)
        }
    }

    // ---- pocket / face-down detection -----------------------------------------------------------

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val near = e.values[0] < e.sensor.maximumRange.coerceAtMost(5f)
            if (near) {
                // Virtual proximity sensors (many Xiaomi/Redmi phones) report a false "near" right
                // after the screen wakes: only treat it as a pocket if it lasts.
                handler.removeCallbacks(coverConfirmed)
                handler.postDelayed(coverConfirmed, COVER_CONFIRM_MS)
            } else {
                handler.removeCallbacks(coverConfirmed)
                handler.removeCallbacks(pocketEnd)
                led.visibility = View.VISIBLE
                info.visibility = View.VISIBLE
                if (peek.isShowing()) peek.visibility = View.VISIBLE
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // Kept after every property declaration: Kotlin runs initialisers top to bottom, and this block
    // reads several of them (sampleApps, yShift, ...). Above them they would still be null.
    init {
        led.useScreenCoords = true
        led.configure(prefs, geometry(), currentColors())
        led.intro = 0f
        updateAgeFade()
        root.addView(info, FrameLayout.LayoutParams(-2, -2))
        val peekWidth = (context.resources.displayMetrics.widthPixels - 48 * density).toInt().coerceAtMost((380 * density).toInt())
        root.addView(peek, FrameLayout.LayoutParams(peekWidth, -2))
        info.onAppTap = { pkg -> onAppTapped(pkg) }
        peek.onOpen = { e -> openNotification(e) }
        peek.onReply = { e -> replyTo(e) }
        peek.onMarkRead = { e -> markRead(e) }
        bindInfo()
        installGestures()
        if (durationSec > 0) handler.postDelayed(timeout, durationSec * 1000L)
        if (preview) handler.postDelayed(timeout, PREVIEW_MS)
    }

    private fun startProximity() {
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        sensorManager = sm
        // No PROXIMITY_SCREEN_OFF_WAKE_LOCK: with it the hardware cuts the panel the instant the
        // sensor says "near", so a false reading right after waking turned the screen straight back
        // off. The black screen at minimum brightness costs next to nothing while covered anyway.
        sm.registerListener(proximityListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
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
        intro?.cancel()
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
    }

    companion object {
        private const val BURN_IN_MS = 60_000L
        private const val POCKET_MS = 10_000L
        private const val COVER_CONFIRM_MS = 1_200L
        /** A screen-off this soon after the panel came on can't be the user's power button. */
        private const val EARLY_OFF_MS = 1_200L
        private const val PREVIEW_MS = 15_000L
        private const val INFO_MS = 8_000L
        private const val ECO_MS = 12_000L
        private const val AGE_TICK_MS = 5 * 60_000L
        private const val AGE_FULL_MS = 60 * 60_000L
        private const val AGE_MIN = 0.45f
        private val SAMPLE_PACKAGES = listOf(
            "com.whatsapp", "org.telegram.messenger", "com.instagram.android", "com.google.android.gm",
            "com.google.android.apps.messaging", "com.facebook.orca", "com.snapchat.android", "com.discord",
            "com.whatsapp.w4b", "com.android.mms", "com.samsung.android.messaging",
        )
        private const val PEEK_MS = 12_000L

        /**
         * Preview without real notifications: messaging apps that are actually installed, so the
         * preview looks like the real thing (instead of Glow LED's own icon).
         */
        fun sampleApps(context: Context): List<NotificationStore.AppInfo> {
            val pm = context.packageManager
            return SAMPLE_PACKAGES.mapNotNull { pkg ->
                try {
                    pm.getApplicationInfo(pkg, 0)
                    NotificationStore.AppInfo(pkg, LedController.colors.colorFor(pkg, null), 1)
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.color }.take(3).ifEmpty {
                listOf(NotificationStore.AppInfo(context.packageName, LedController.prefs.defaultColor, 1))
            }
        }
        private const val DOUBLE_TAP_MS = 350L
        private const val TAP_MAX_MS = 300L

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

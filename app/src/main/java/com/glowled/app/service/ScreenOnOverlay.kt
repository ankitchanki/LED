package com.glowled.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.glowled.app.core.CutoutGeometry
import com.glowled.app.core.DebugLog
import com.glowled.app.core.Geometry
import com.glowled.app.core.LedController
import com.glowled.app.core.MeterIcon
import com.glowled.app.core.NotificationStore
import com.glowled.app.ui.ReplyActivity
import com.glowled.app.view.AppInfoView
import com.glowled.app.view.LedView
import com.glowled.app.view.NotificationPeekView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Interactive notification overlay for screen-on (unlocked) use.
 *
 *  - collapsed: ring around the camera only; tap the ring to expand;
 *  - icons: one circle/icon per source app below the camera; tap an icon to read;
 *  - peek: message card for the selected app, with reply / mark-as-read / open.
 *
 * Actions mirror the screen-off message view. The window is sized to the visible UI so touches
 * outside it reach the apps below.
 */
@SuppressLint("ClickableViewAccessibility")
object ScreenOnOverlay {

    private enum class State { COLLAPSED, ICONS, PEEK }

    private var state = State.COLLAPSED
    private var root: RootLayout? = null
    private var led: LedView? = null
    private var info: AppInfoView? = null
    private var peek: NotificationPeekView? = null
    private var wm: WindowManager? = null
    private var windowCtx: Context? = null
    private var usingA11y = false

    private val main = Handler(Looper.getMainLooper())
    private val hidePeekRunnable = Runnable { hidePeek(showIconsAfter = true) }

    private var peekPkg: String? = null
    private var newestTime = 0L

    private val listener: () -> Unit = { main.post { onStoreChanged() } }

    private open class RootLayout(context: Context) : FrameLayout(context) {
        var onLaidOut: (() -> Unit)? = null

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            onLaidOut?.invoke()
        }
    }

    fun isShowing(): Boolean = root != null

    /** True when the screen-on overlay should be the interactive (tappable) one. */
    fun shouldBeInteractive(): Boolean {
        val p = LedController.prefs
        return p.overlayEnabled && p.onTapMessages && !NotificationStore.isEmpty() &&
            !LedController.isLocked() && !p.overlayLockOnly && !p.style.isEdge
    }

    fun show(context: Context) {
        val prefs = LedController.prefs
        if (!prefs.enabled) return hide()
        // Prefer accessibility overlay for interactive/touch input (better cutout touch behavior on some devices).
        val a11y = LedAccessibilityService.instance
        val wantA11y = a11y != null
        if (root != null && usingA11y == wantA11y) {
            refresh()
            return
        }
        hide()
        val ctx: Context
        val type: Int
        if (a11y != null) {
            ctx = a11y; type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Settings.canDrawOverlays(context)) {
            ctx = context.applicationContext; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            DebugLog.add("Screen-on overlay: no permission/service")
            return
        }

        val r = object : RootLayout(ctx) {
            private var downX = 0f; private var downY = 0f; private var downT = 0L
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    downX = ev.rawX; downY = ev.rawY; downT = ev.eventTime
                    val loc = IntArray(2); getLocationOnScreen(loc)
                    DebugLog.add("Screen-on touch down ($state) at ${ev.rawX.toInt()},${ev.rawY.toInt()} win=${loc[0]},${loc[1]} ${width}x${height}")
                }
                val handled = super.dispatchTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    val slop = 48 * resources.displayMetrics.density
                    val isTap = ev.eventTime - downT < 400 && abs(ev.rawX - downX) < slop && abs(ev.rawY - downY) < slop
                    if (isTap && !hitInteractive(ev)) {
                        DebugLog.add("Screen-on ring tapped ($state)")
                        onBackgroundTap()
                    }
                }
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) return true
                return handled || ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
            }
            private fun hitInteractive(ev: MotionEvent): Boolean {
                val i = info
                if (i != null && i.visibility == View.VISIBLE && pointInView(i, ev.rawX, ev.rawY)) return true
                val p = peek
                if (p != null && p.isShowing() && pointInView(p, ev.rawX, ev.rawY)) return true
                return false
            }
            private fun pointInView(v: View, rawX: Float, rawY: Float): Boolean {
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                return rawX >= loc[0] && rawX <= loc[0] + v.width && rawY >= loc[1] && rawY <= loc[1] + v.height
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isForceDarkAllowed = false
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = false
        }

        val ledView = LedView(ctx).apply { useScreenCoords = true; intro = 1f; dropletRadiusDp = prefs.dropletSizeDp.toFloat() }
        val infoView = AppInfoView(ctx).apply {
            monochrome = prefs.aodIcons
            iconDp = prefs.appIconDp
            onAppTap = { pkg -> onAppTapped(pkg) }
        }
        val d = ctx.resources.displayMetrics.density
        val peekWidth = (prefs.peekWidthDp * d).toInt().coerceIn((180 * d).toInt(), (ctx.resources.displayMetrics.widthPixels - (24 * d).toInt()))
        val peekView = NotificationPeekView(ctx).apply {
            actionsEnabled = prefs.peekActions
            expandableEnabled = prefs.peekExpandable
            applyCustomization(prefs.peekFontSizeSp.toFloat(), prefs.peekLines, prefs.peekHeightDp)
            onOpen = { e -> openNotification(e) }
            onReply = { e -> replyTo(e) }
            onMarkRead = { e -> markRead(e) }
            onMarkAllRead = { list -> markAllRead(list) }
            onInteraction = { resetPeekTimeout() }
            onExpandedChanged = {
                resetPeekTimeout()
                val type = windowType()
                if (type != 0) applyWindow(type)
                root?.requestLayout()
                root?.post { positionChildren() }
            }
        }

        r.addView(ledView, FrameLayout.LayoutParams(-1, -1))
        r.addView(infoView, FrameLayout.LayoutParams(-2, -2))
        r.addView(peekView, FrameLayout.LayoutParams(peekWidth, -2))
        r.onLaidOut = { positionChildren() }

        root = r; led = ledView; info = infoView; peek = peekView
        windowCtx = ctx; usingA11y = wantA11y
        wm = ctx.getSystemService(WindowManager::class.java)
        state = State.COLLAPSED; peekPkg = null
        newestTime = NotificationStore.newestTime()

        applyWindow(type)
        if (root == null) return
        LedController.addListener(listener)
        DebugLog.add("Screen-on interactive overlay shown (${if (wantA11y) "a11y" else "app"})")
        refresh()
    }

    private fun geometry(ctx: Context): Geometry = CutoutGeometry.resolve(ctx, LedController.prefs)

    private fun extentPx(g: Geometry, prefs: com.glowled.app.core.Prefs, d: Float): Int {
        val thickness = prefs.thicknessDp * d
        val gap = prefs.gapDp * d
        val glowRadius = if (prefs.glow) thickness * 1.6f + 3 * d else 0f
        val half = max(g.w, g.h) / 2f + gap + thickness
        return (half + thickness * 7 + glowRadius * 2 + 4 * d).toInt()
    }

    /** Screen-space tap/response zone for the collapsed screen-on ring. */
    fun tapZoneRect(ctx: Context): android.graphics.Rect {
        val prefs = LedController.prefs
        val g = geometry(ctx)
        val d = ctx.resources.displayMetrics.density
        val e = extentPx(g, prefs, d)
        val ringW = maxOf(e * 2, (120 * d).toInt())
        val autoW = ringW
        val autoH = (g.cy.toInt() + e + (DROP_DP * d).toInt() * 2 + (120 * d).toInt())
            .coerceAtLeast(e * 2 + (DROP_DP * d).toInt() * 2)
        val w = if (prefs.tapZoneWidthDp > 0) (prefs.tapZoneWidthDp * d).toInt().coerceAtLeast((40 * d).toInt()) else autoW
        val h = if (prefs.tapZoneHeightDp > 0) (prefs.tapZoneHeightDp * d).toInt().coerceAtLeast((40 * d).toInt()) else autoH
        val dm = ctx.resources.displayMetrics
        var x = (g.cx - w / 2f).toInt() + (prefs.tapZoneOffsetXDp * d).toInt()
        var y = 0 + (prefs.tapZoneOffsetYDp * d).toInt()
        // Keep on screen.
        x = x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        y = y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
        return android.graphics.Rect(x, y, x + w, y + h)
    }

    private fun currentParams(): WindowManager.LayoutParams? = root?.layoutParams as? WindowManager.LayoutParams
    private fun windowType(): Int = currentParams()?.type ?: 0

    private fun applyWindow(type: Int) {
        val r = root ?: return
        val ctx = windowCtx ?: return
        val manager = wm ?: return
        val p = buildParams(ctx, type)
        if (r.parent == null) {
            try { manager.addView(r, p) } catch (e: Exception) {
                Log.w("GlowLED", "interactive overlay add failed", e); cleanupRefs(); return
            }
        } else {
            try { manager.updateViewLayout(r, p) } catch (e: Exception) {
                Log.w("GlowLED", "interactive overlay update failed", e)
            }
        }
    }

    private fun buildParams(ctx: Context, type: Int): WindowManager.LayoutParams {
        val prefs = LedController.prefs
        val g = geometry(ctx)
        val ledView = led!!
        val d = ctx.resources.displayMetrics.density
        ledView.configure(prefs, g, NotificationStore.colors())
        ledView.notificationRingVisible = prefs.overlayEnabled && !NotificationStore.isEmpty() &&
            (!prefs.overlayLockOnly || LedController.isLocked())
        val m = LedController.overlayMeter
        ledView.setMeter(m?.fraction, m?.color ?: 0, m?.icon ?: MeterIcon.NONE, m?.indeterminate ?: false)
        ledView.setBattery(LedController.batteryPercent())

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val p: WindowManager.LayoutParams
        if (prefs.style.isEdge) {
            p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                type, flags, PixelFormat.TRANSLUCENT,
            )
            p.gravity = Gravity.TOP or Gravity.START
        } else {
            val e = ledView.extentPx(g)
            val peekW = (prefs.peekWidthDp * d).toInt().coerceIn((180 * d).toInt(), (ctx.resources.displayMetrics.widthPixels - (24 * d).toInt()))
            val ringW = maxOf(e * 2, (120 * d).toInt())
            val expandedW = maxOf(ringW, peekW + (24 * d).toInt(), (220 * d).toInt())
            if (state == State.COLLAPSED) {
                val tz = tapZoneRect(ctx)
                p = WindowManager.LayoutParams(tz.width(), tz.height(), type, flags, PixelFormat.TRANSLUCENT)
                p.gravity = Gravity.TOP or Gravity.START
                p.x = tz.left; p.y = tz.top
            } else {
                val w = expandedW
                val x = (g.cx - w / 2f).toInt()
                val y = (g.cy - e).toInt()
                val h = when (state) {
                    State.ICONS -> e + (300 * d).toInt()
                    State.PEEK -> (ctx.resources.displayMetrics.heightPixels - y).coerceAtLeast(e + (prefs.peekHeightDp * d).toInt() + (80 * d).toInt())
                    else -> e * 2
                }
                p = WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT)
                p.gravity = Gravity.TOP or Gravity.START
                p.x = x; p.y = y
            }
        }
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) p.fitInsetsTypes = 0
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.alpha = 0.8f
        }
        p.title = "GlowLED"
        return p
    }

    fun refresh() {
        val r = root ?: return
        val ctx = windowCtx ?: return
        val prefs = LedController.prefs
        val ledView = led ?: return
        val g = geometry(ctx)
        ledView.configure(prefs, g, NotificationStore.colors())
        ledView.notificationRingVisible = prefs.overlayEnabled && !NotificationStore.isEmpty() &&
            (!prefs.overlayLockOnly || LedController.isLocked())
        val m = LedController.overlayMeter
        ledView.setMeter(m?.fraction, m?.color ?: 0, m?.icon ?: MeterIcon.NONE, m?.indeterminate ?: false)
        ledView.setBattery(LedController.batteryPercent())
        ledView.dropletRadiusDp = prefs.dropletSizeDp.toFloat()
        info?.monochrome = prefs.aodIcons
        info?.iconDp = prefs.appIconDp

        val d = ctx.resources.displayMetrics.density
        val peekW = (prefs.peekWidthDp * d).toInt().coerceIn((180 * d).toInt(), (ctx.resources.displayMetrics.widthPixels - (24 * d).toInt()))
        peek?.layoutParams?.width = peekW
        peek?.actionsEnabled = prefs.peekActions
        peek?.expandableEnabled = prefs.peekExpandable
        peek?.applyCustomization(prefs.peekFontSizeSp.toFloat(), prefs.peekLines, prefs.peekHeightDp)

        val type = windowType()
        if (type != 0) applyWindow(type)
        r.requestLayout()
        r.post { positionChildren() }
    }

    private fun positionChildren() {
        val r = root ?: return
        val ctx = windowCtx ?: return
        val ledView = led ?: return
        val infoView = info ?: return
        val peekView = peek ?: return
        if (!r.isAttachedToWindow) return
        val loc = IntArray(2)
        r.getLocationOnScreen(loc)
        val g = geometry(ctx)
        val d = ctx.resources.displayMetrics.density
        val safeTop = max(ledView.indicatorBottom() + 16 * d, 8 * d)
        val infoX = g.cx - loc[0] - infoView.measuredWidth / 2f
        val infoY = safeTop
        place(infoView, infoX, infoY)
        val peekY = infoY + (if (infoView.visibility == View.VISIBLE) infoView.measuredHeight + 14 * d else 0f)
        place(peekView, g.cx - loc[0] - peekView.measuredWidth / 2f, peekY)
    }

    private fun place(v: View, x: Float, y: Float) {
        val l = x.roundToInt(); val t = y.roundToInt()
        v.layout(l, t, l + v.measuredWidth, t + v.measuredHeight)
    }

    private fun setState(newState: State) {
        if (state == newState && root != null) return
        state = newState
        val type = windowType()
        if (type != 0) applyWindow(type)
        root?.requestLayout()
        root?.post { positionChildren() }
    }

    private fun bindApps() { info?.bind(NotificationStore.apps()) }

    private fun expandToIcons(withDroplet: Boolean = false) {
        val prefs = LedController.prefs
        bindApps()
        // show icons (even if one app) to satisfy "раскрывается несколько кружков"
        if (state == State.COLLAPSED) setState(State.ICONS) else setState(State.ICONS)
        main.removeCallbacks(hidePeekRunnable)
        val newest = NotificationStore.apps().firstOrNull()
        if (withDroplet && prefs.droplet && newest != null) {
            info?.visibility = View.VISIBLE
            info?.alpha = 1f
            info?.doOnLayout {
                if (root == null) return@doOnLayout
                info?.prepareDrop()
                val ctx = windowCtx ?: return@doOnLayout
                val loc = IntArray(2); root!!.getLocationOnScreen(loc)
                val g = geometry(ctx)
                val infoView = info ?: return@doOnLayout
                val infoX = g.cx - loc[0] - infoView.measuredWidth / 2f
                val tx = infoX + infoView.newestCenterX()
                val ty = infoView.top.toFloat() + infoView.iconCenterY()
                led?.playDroplet(newest.color, ty, targetX = tx) { info?.popNewest() }
            }
        } else {
            info?.show()
        }
    }

    private fun collapseToRing() {
        main.removeCallbacks(hidePeekRunnable)
        hidePeek(showIconsAfter = false)
        info?.clearSelection()
        info?.hide()
        setState(State.COLLAPSED)
    }

    private fun onAppTapped(pkg: String) {
        val prefs = LedController.prefs
        main.removeCallbacks(hidePeekRunnable)
        if (!prefs.showContent) {
            info?.clearSelection()
            return
        }
        val entries = NotificationStore.entriesFor(pkg)
        if (entries.isEmpty() || (peek?.isShowing() == true && peekPkg == pkg)) {
            hidePeek(showIconsAfter = true)
            return
        }
        peekPkg = pkg
        peek?.actionsEnabled = prefs.peekActions
        peek?.expandableEnabled = prefs.peekExpandable
        peek?.applyCustomization(prefs.peekFontSizeSp.toFloat(), prefs.peekLines, prefs.peekHeightDp)
        peek?.bind(entries)
        peek?.show()
        setState(State.PEEK)
        root?.post { positionChildren() }
        resetPeekTimeout()
    }

    private fun resetPeekTimeout() {
        main.removeCallbacks(hidePeekRunnable)
        main.postDelayed(hidePeekRunnable, PEEK_MS)
    }

    private fun hidePeek(showIconsAfter: Boolean) {
        main.removeCallbacks(hidePeekRunnable)
        peekPkg = null
        peek?.hide()
        info?.clearSelection()
        if (state == State.PEEK) setState(State.ICONS)
    }

    private fun onBackgroundTap() {
        when (state) {
            State.PEEK -> hidePeek(showIconsAfter = true)
            State.ICONS -> collapseToRing()
            State.COLLAPSED -> {
                if (!NotificationStore.isEmpty()) expandToIcons(withDroplet = true)
                else DebugLog.add("Screen-on tap: no notifications")
            }
        }
    }

    // ---- actions (same as AOD, but on unlocked screen) ----

    private fun openNotification(e: NotificationStore.Entry) {
        val pi = e.intent ?: return
        root?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        try {
            val opts = android.app.ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                opts.setPendingIntentBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
            }
            pi.send(windowCtx ?: LedController.app, 0, null, null, null, null, opts.toBundle())
        } catch (ex: Exception) {
            DebugLog.add("Could not open notification: ${ex.javaClass.simpleName}")
        }
        collapseToRing()
    }

    private fun replyTo(e: NotificationStore.Entry) {
        root?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        if (ReplyActivity.start(LedController.app, e.key)) collapseToRing()
    }

    private fun markRead(e: NotificationStore.Entry) {
        root?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        val action = e.readAction
        if (action != null) {
            try { action.actionIntent.send() } catch (ex: Exception) {
                DebugLog.add("Mark as read failed: ${ex.javaClass.simpleName}")
            }
            NotificationStore.markSeen(e.key)
            LedController.refreshAll()
        } else {
            NotifyListenerService.instance?.cancelNotification(e.key)
        }
        DebugLog.add("Marked as read from screen-on overlay")
        // store change will update; keep UX responsive
        if (NotificationStore.isEmpty()) collapseToRing() else {
            if (NotificationStore.entriesFor(peekPkg ?: "").isEmpty()) hidePeek(true)
        }
    }

    private fun markAllRead(list: List<NotificationStore.Entry>) {
        root?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        for (e in list) {
            val action = e.readAction
            if (action != null) {
                try { action.actionIntent.send() } catch (ex: Exception) {
                    DebugLog.add("Mark as read failed: ${ex.javaClass.simpleName}")
                }
                NotificationStore.markSeen(e.key)
            } else {
                NotifyListenerService.instance?.cancelNotification(e.key)
            }
        }
        LedController.refreshAll()
        collapseToRing()
    }

    private fun onStoreChanged() {
        if (root == null) return
        if (NotificationStore.isEmpty()) {
            // let controller decide (may keep overlay for meter/battery) -> will hide interactive
            LedController.updateOverlay()
            return
        }
        val apps = NotificationStore.apps()
        if (apps.isEmpty()) { LedController.updateOverlay(); return }
        bindApps()
        val newest = NotificationStore.newestTime()
        if (newest > newestTime) {
            val newestApp = apps.firstOrNull()
            newestTime = newest
            if (state != State.COLLAPSED && newestApp != null && LedController.prefs.droplet) {
                info?.doOnLayout {
                    if (root == null) return@doOnLayout
                    info?.prepareDrop()
                    val ctx = windowCtx ?: return@doOnLayout
                    val loc = IntArray(2); root!!.getLocationOnScreen(loc)
                    val g = geometry(ctx)
                    val infoView = info ?: return@doOnLayout
                    val infoX = g.cx - loc[0] - infoView.measuredWidth / 2f
                    val tx = infoX + infoView.newestCenterX()
                    val ty = infoView.top.toFloat() + infoView.iconCenterY()
                    led?.playDroplet(newestApp.color, ty, targetX = tx) { info?.popNewest() }
                }
            } else if (state != State.COLLAPSED) {
                info?.show()
            }
            // collapsed: ring droplet handled by LedController/OverlayHost path
        }
        peekPkg?.let { pkg ->
            val entries = NotificationStore.entriesFor(pkg)
            if (entries.isEmpty()) hidePeek(showIconsAfter = true) else peek?.bind(entries)
        }
        refresh()
        root?.post { positionChildren() }
    }

    /** Droplet on the ring while collapsed. */
    fun droplet(color: Int) {
        val ledView = led ?: run {
            DebugLog.add("Droplet skipped: no overlay view")
            return
        }
        if (state == State.COLLAPSED) {
            DebugLog.add("Droplet on screen-on ring")
            ledView.post { ledView.playDropletBelow(color, DROP_DP * ledView.resources.displayMetrics.density, retract = true) }
        } else {
            DebugLog.add("Droplet skipped: state=$state")
        }
    }

    fun hide() {
        val r = root ?: return
        try { LedController.removeListener(listener) } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        try { wm?.removeViewImmediate(r) } catch (_: Exception) {}
        cleanupRefs()
    }

    private fun cleanupRefs() {
        root = null; led = null; info = null; peek = null; wm = null; windowCtx = null
        usingA11y = false; state = State.COLLAPSED; peekPkg = null
    }

    private const val DROP_DP = 26f
    private const val PEEK_MS = 12_000L
}

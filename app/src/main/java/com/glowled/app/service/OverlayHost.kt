package com.glowled.app.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.glowled.app.core.CutoutGeometry
import com.glowled.app.core.LedController
import com.glowled.app.core.NotificationStore
import com.glowled.app.view.LedView

/**
 * Screen-on indicator window. Uses an accessibility overlay when available (shows on the lock
 * screen and above the status bar); otherwise a "display over other apps" window.
 * The window is sized tightly around the camera so the compositor has almost nothing to blend.
 */
object OverlayHost {

    private var view: LedView? = null
    private var wm: WindowManager? = null
    private var usingA11y = false
    private val listener: () -> Unit = { refresh() }

    fun show(context: Context) {
        val a11y = LedAccessibilityService.instance
        if (view != null && usingA11y == (a11y != null)) {
            refresh()
            return
        }
        hide()
        val ctx: Context
        val type: Int
        if (a11y != null) {
            ctx = a11y
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Settings.canDrawOverlays(context)) {
            ctx = context.applicationContext
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            return
        }
        val manager = ctx.getSystemService(WindowManager::class.java)
        val v = LedView(ctx)
        val params = buildParams(ctx, v, type)
        try {
            manager.addView(v, params)
        } catch (e: Exception) {
            Log.w("GlowLED", "overlay add failed", e)
            return
        }
        view = v
        wm = manager
        usingA11y = a11y != null
        LedController.addListener(listener)
    }

    private fun buildParams(ctx: Context, v: LedView, type: Int): WindowManager.LayoutParams {
        val prefs = LedController.prefs
        val g = CutoutGeometry.resolve(ctx, prefs)
        v.configure(prefs, g, NotificationStore.colors())
        v.notificationRingVisible = prefs.overlayEnabled && !NotificationStore.isEmpty() &&
            (!prefs.overlayLockOnly || LedController.isLocked())
        val m = LedController.overlayMeter
        v.setMeter(m?.fraction, m?.color ?: 0, m?.label, m?.indeterminate ?: false)
        v.setBattery(LedController.batteryPercent())
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
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
            val e = v.extentPx(g)
            // Extra room below the ring for the droplet that drips out on a new notification.
            val dropRoom = (DROP_DP * ctx.resources.displayMetrics.density).toInt() * 2
            val width = maxOf(e * 2, (80 * ctx.resources.displayMetrics.density).toInt())
            p = WindowManager.LayoutParams(width, e * 2 + dropRoom, type, flags, PixelFormat.TRANSLUCENT)
            p.gravity = Gravity.TOP or Gravity.START
            p.x = (g.cx - p.width / 2f).toInt()
            p.y = (g.cy - e).toInt()
        }
        p.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) p.fitInsetsTypes = 0
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ blocks touches through untrusted overlays above 0.8 alpha.
            p.alpha = 0.8f
        }
        p.title = "GlowLED"
        return p
    }

    /** Re-applies settings, colours and the gauge; repositions the window if geometry or style changed. */
    fun refresh() {
        val v = view ?: return
        val manager = wm ?: return
        val type = (v.layoutParams as? WindowManager.LayoutParams)?.type ?: return
        val old = v.layoutParams as? WindowManager.LayoutParams ?: return
        val p = buildParams(v.context, v, type)
        // Colours and the gauge are applied to the view directly; only move the window if needed.
        if (p.x == old.x && p.y == old.y && p.width == old.width && p.height == old.height) return
        try {
            manager.updateViewLayout(v, p)
        } catch (e: Exception) {
            Log.w("GlowLED", "overlay update failed", e)
        }
    }

    /** New notification while the screen is on: a drop stretches out of the camera and springs back. */
    fun droplet(color: Int) {
        val v = view ?: return
        // Post: the window may have just been added and not laid out yet.
        v.post { v.playDropletBelow(color, DROP_DP * v.resources.displayMetrics.density, retract = true) }
    }

    private const val DROP_DP = 26f

    fun hide() {
        val v = view ?: return
        LedController.removeListener(listener)
        v.visibility = View.GONE
        try {
            wm?.removeViewImmediate(v)
        } catch (_: Exception) {
        }
        view = null
        wm = null
    }
}

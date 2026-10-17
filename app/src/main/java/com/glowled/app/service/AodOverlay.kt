package com.glowled.app.service

import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.glowled.app.core.DebugLog
import com.glowled.app.core.LedController
import com.glowled.app.ui.AodSession

/**
 * Always-on light drawn in a full-screen accessibility overlay. Unlike an activity, an overlay
 * window is not a "background activity start", so HyperOS/MIUI, ColorOS, Funtouch etc. can't block
 * it. It sits above the lock screen; the screen itself is woken with a short wake lock.
 */
object AodOverlay {

    private var session: AodSession? = null
    private val main = Handler(Looper.getMainLooper())

    val isShowing get() = session != null

    fun show(durationSec: Int, preview: Boolean): Boolean {
        val service = LedAccessibilityService.instance ?: return false
        session?.let {
            it.restartAnimation()
            return true
        }
        val wm = service.getSystemService(WindowManager::class.java)
        lateinit var s: AodSession
        s = AodSession(service, preview, durationSec, object : AodSession.Host {
            override fun close(lock: Boolean) {
                if (lock && LedController.turnScreenOff()) {
                    // Remove after the panel is off so the lock screen never flashes.
                    main.postDelayed({ remove(s) }, 350)
                } else {
                    remove(s)
                }
            }

            override fun releaseKeepScreenOn() {
                val lp = s.root.layoutParams as? WindowManager.LayoutParams ?: return
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                try {
                    wm.updateViewLayout(s.root, lp)
                } catch (_: Exception) {
                }
            }
        })
        val prefs = LedController.prefs
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        )
        lp.screenBrightness = (prefs.aodBrightness / 100f).coerceIn(0.01f, 1f)
        lp.buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lp.fitInsetsTypes = 0
        if (prefs.lowRefresh) AodSession.lowestModeId(service)?.let {
            lp.preferredDisplayModeId = it.first
            lp.preferredRefreshRate = it.second
        }
        lp.title = "GlowLED AOD"
        try {
            wm.addView(s.root, lp)
        } catch (e: Exception) {
            DebugLog.add("Overlay failed: ${e.javaClass.simpleName}")
            return false
        }
        session = s
        s.start()
        return true
    }

    private fun remove(s: AodSession) {
        if (session === s) session = null
        s.onHostGone()
        try {
            LedAccessibilityService.instance?.getSystemService(WindowManager::class.java)?.removeViewImmediate(s.root)
        } catch (_: Exception) {
        }
    }

    /** Service is going away: drop the window. */
    fun forceRemove() {
        session?.let { remove(it) }
    }
}

package com.glowled.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.glowled.app.core.LedController
import com.glowled.app.core.LedController.EndReason

/**
 * Activity host for [AodSession], used when the accessibility overlay isn't available.
 * Shows over the lock screen and turns the screen on by itself.
 */
class AodActivity : Activity() {

    private lateinit var session: AodSession
    private var resumedAt = 0L
    private var waitingForOff = false

    private val finishOnOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LedController.init(this)
        com.glowled.app.core.DebugLog.add("Lock-screen window created, ${LedController.state()}")
        getSystemService(NotificationManager::class.java).cancel(LedController.FSI_ID)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                // Legacy full-screen flags: HyperOS/MIUI only hides the lock-screen status bar for
                // windows that ask for it this way.
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        configureWindow()

        val preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        val seconds = intent.getIntExtra(EXTRA_DURATION, LedController.prefs.aodTimeoutSec)
        session = AodSession(this, preview, seconds, object : AodSession.Host {
            override fun close(lock: Boolean) {
                if (lock) LedController.turnScreenOff()
                finish()
            }

            override fun releaseKeepScreenOn() {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                ContextCompat.registerReceiver(
                    this@AodActivity, finishOnOff, IntentFilter(Intent.ACTION_SCREEN_OFF),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                waitingForOff = true
            }

            // Show the PIN/fingerprint prompt, then open the chat once unlocked.
            override fun dismissKeyguard(then: () -> Unit) {
                val km = getSystemService(KeyguardManager::class.java)
                if (!km.isKeyguardLocked) return then()
                km.requestDismissKeyguard(this@AodActivity, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = then()
                })
            }
        })
        setContentView(session.root)
        hideSystemBars(session.root)
        session.start()
    }

    private fun configureWindow() {
        val prefs = LedController.prefs
        // Keep the lock screen/wallpaper out of the AOD composition, including before
        // the session's first frame. Only the LED and notification content emit colour.
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.setFormat(PixelFormat.OPAQUE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        val lp = window.attributes
        lp.alpha = 1f
        lp.dimAmount = 0f
        lp.screenBrightness = (prefs.aodBrightness / 100f).coerceIn(0.01f, 1f)
        lp.buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (prefs.lowRefresh) AodSession.lowestModeId(this)?.let {
            lp.preferredDisplayModeId = it.first
            lp.preferredRefreshRate = it.second
        }
        window.attributes = lp
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars(root: View) {
        // Both the legacy flags and the insets controller: OEM lock screens honour one or the other.
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A new notification arrived while fading out: start a fresh session.
        if (session.ended) recreate() else session.restartAnimation()
    }

    override fun onResume() {
        super.onResume()
        com.glowled.app.core.DebugLog.add("Lock-screen window resumed, ${LedController.state()}")
        if (resumedAt == 0L) resumedAt = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        // Paused while still showing = something (usually the lock screen) is drawn over it.
        com.glowled.app.core.DebugLog.add("Lock-screen window paused (covered), ${LedController.state()}")
    }

    override fun onStop() {
        super.onStop()
        com.glowled.app.core.DebugLog.add("Lock-screen window stopped, ${LedController.state()}")
        // Another activity (e.g. an incoming call) took over. Ignore the brief stop some ROMs
        // send while the screen is still turning on.
        val settled = resumedAt != 0L && System.currentTimeMillis() - resumedAt > 1500
        if (!session.ended && settled && !isChangingConfigurations) session.end(EndReason.USER, lock = false)
    }

    override fun onDestroy() {
        com.glowled.app.core.DebugLog.add("Lock-screen window closed")
        session.onHostGone()
        if (waitingForOff) {
            try {
                unregisterReceiver(finishOnOff)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        session.end(EndReason.USER, lock = false)
    }

    companion object {
        const val EXTRA_DURATION = "duration"
        const val EXTRA_PREVIEW = "preview"
    }
}

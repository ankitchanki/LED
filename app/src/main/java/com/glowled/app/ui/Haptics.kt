package com.glowled.app.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.glowled.app.core.LedController

/**
 * Haptic feedback for the settings. Sliders tick harder as the value goes up and softer as it goes
 * down; switches give a firm click when turned on and a lighter one when turned off. Uses the
 * phone's crisp haptic primitives where available (most phones with a good vibration motor),
 * otherwise a short pulse with matching strength, otherwise the system's standard tick.
 * Respects the phone's touch-feedback setting and the in-app switch.
 */
object Haptics {

    private var lastTick = 0L

    /** A tick whose strength follows [fraction] (0 = weakest, 1 = strongest), e.g. a slider position. */
    fun level(v: View, fraction: Float) {
        if (!enabled(v.context)) return
        // Fast drags fire many value changes: keep the ticks distinct instead of a buzz.
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < MIN_TICK_GAP_MS) return
        lastTick = now
        val f = fraction.coerceIn(0f, 1f)
        play(v, PRIMITIVE_TICK, 0.12f + 0.88f * f, (24 + 231 * f).toInt(), 8L,
            HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Switch flipped: firm when it turns on, light when it turns off. */
    fun toggle(v: View, on: Boolean) {
        if (!enabled(v.context)) return
        play(v, PRIMITIVE_CLICK, if (on) 1f else 0.45f, if (on) 255 else 110, if (on) 18L else 10L,
            HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** Ordinary tap (chips, tiles, buttons). */
    fun click(v: View) {
        if (!enabled(v.context)) return
        play(v, PRIMITIVE_CLICK, 0.7f, 180, 12L, HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private const val MIN_TICK_GAP_MS = 22L
    private const val PRIMITIVE_TICK = 7 // VibrationEffect.Composition.PRIMITIVE_TICK
    private const val PRIMITIVE_CLICK = 1 // VibrationEffect.Composition.PRIMITIVE_CLICK

    private fun enabled(c: Context): Boolean {
        if (!LedController.prefs.haptics) return false
        // The phone's own "Touch feedback" switch.
        return Settings.System.getInt(c.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
    }

    private fun vibrator(c: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            c.getSystemService(Vibrator::class.java)
        }

    private fun play(v: View, primitive: Int, scale: Float, amplitude: Int, ms: Long, fallback: Int) {
        val vib = vibrator(v.context)
        try {
            when {
                vib == null || !vib.hasVibrator() -> v.performHapticFeedback(fallback)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vib.areAllPrimitivesSupported(primitive) ->
                    vibrate(vib, VibrationEffect.startComposition().addPrimitive(primitive, scale.coerceIn(0f, 1f)).compose())
                vib.hasAmplitudeControl() ->
                    vibrate(vib, VibrationEffect.createOneShot(ms, amplitude.coerceIn(1, 255)))
                else -> v.performHapticFeedback(fallback)
            }
        } catch (_: Exception) {
            v.performHapticFeedback(fallback)
        }
    }

    private fun vibrate(vib: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Touch usage: follows the system's touch-feedback intensity.
            vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            vib.vibrate(effect)
        }
    }
}

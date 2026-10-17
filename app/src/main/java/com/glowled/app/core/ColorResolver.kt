package com.glowled.app.core

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette

/** Picks the LED color for a notification: user override → notification accent → icon color → default. */
class ColorResolver(private val context: Context, private val prefs: Prefs) {

    private val iconCache = LruCache<String, Int>(64)

    fun colorFor(pkg: String, n: Notification?): Int {
        val override = prefs.appColor(pkg)
        if (override != 0) return opaque(override)

        if (prefs.useNotificationColor && n != null) {
            val c = n.color
            if (c != Notification.COLOR_DEFAULT && isUsable(c)) return opaque(c)
        }
        if (prefs.useIconColor) {
            val c = iconColor(pkg)
            if (c != 0) return c
        }
        return opaque(prefs.defaultColor)
    }

    fun iconColor(pkg: String): Int {
        iconCache.get(pkg)?.let { return it }
        val color = try {
            val drawable = context.packageManager.getApplicationIcon(pkg)
            val bmp = drawable.toBitmap(48, 48, Bitmap.Config.ARGB_8888)
            val p = Palette.from(bmp).clearFilters().maximumColorCount(12).generate()
            val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
            val c = sw?.rgb ?: 0
            if (c != 0 && isUsable(c)) boost(c) else 0
        } catch (_: Exception) {
            0
        }
        iconCache.put(pkg, color)
        return color
    }

    /** Rejects near-black/near-grey colors that would be invisible or dull on AMOLED. */
    private fun isUsable(c: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        return hsv[2] > 0.25f && hsv[1] > 0.2f
    }

    /** Pushes saturation/value up so the LED glows on a black screen. */
    private fun boost(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = hsv[1].coerceAtLeast(0.65f)
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }

    private fun opaque(c: Int) = c or 0xFF000000.toInt()
}

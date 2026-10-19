package com.glowled.app.view

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * Background for the frosted-glass look: black with a few large, very soft colour glows that the
 * see-through cards sit on (like light behind frosted glass). Gradients are soft by nature, so this
 * needs no blur pass and draws once - nothing animates. Only used in the settings screens; the
 * always-on light stays pure black.
 */
class GlassBackdrop(private var glows: IntArray) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Up to three glow colours (e.g. the accent and the user's LED colour). */
    fun setGlows(colors: IntArray) {
        if (colors.contentEquals(glows)) return
        glows = colors
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(Color.BLACK)
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val r = max(w, h)
        // (x, y, radius, strength) of each glow, as fractions of the screen.
        val spots = arrayOf(
            floatArrayOf(0.1f, 0.12f, 0.75f, 0.42f),
            floatArrayOf(0.95f, 0.45f, 0.7f, 0.32f),
            floatArrayOf(0.2f, 0.95f, 0.8f, 0.36f),
        )
        spots.forEachIndexed { i, s ->
            val c = glows.getOrNull(i) ?: glows.lastOrNull() ?: return
            val cx = b.left + s[0] * w
            val cy = b.top + s[1] * h
            val rad = s[2] * r
            val center = Color.argb((255 * s[3]).toInt(), Color.red(c), Color.green(c), Color.blue(c))
            val mid = Color.argb((255 * s[3] * 0.35f).toInt(), Color.red(c), Color.green(c), Color.blue(c))
            paint.shader = RadialGradient(cx, cy, rad, intArrayOf(center, mid, Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, rad, paint)
        }
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

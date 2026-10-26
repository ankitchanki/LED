package com.glowled.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.glowled.app.core.MeterIcon
import kotlin.math.min

/**
 * A still transfer ring for the settings screens: track, progress from the top and the sign in a
 * gap at the bottom middle, drawn like the real ring around the camera.
 */
class ArrowRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var color = 0xFF2979FF.toInt()
        set(v) { field = v; invalidate() }
    var fraction = 0.6f
        set(v) { field = v; invalidate() }
    var icon = MeterIcon.DOWN
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val sign = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val box = RectF()
    private val path = Path()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(c: Canvas) {
        val size = min(width, height).toFloat()
        val stroke = size * 0.075f
        val r = size / 2f - stroke * 1.6f
        val cx = width / 2f
        val cy = height / 2f
        box.set(cx - r, cy - r, cx + r, cy + r)
        ring.strokeWidth = stroke
        val iconH = size * 0.2f
        // Degrees left open around the bottom (90°) for the sign.
        val gap = if (icon == MeterIcon.NONE) 0f else Math.toDegrees(((iconH * 0.62f + stroke) / r).toDouble()).toFloat()
        ring.color = color
        ring.alpha = 46
        arc(c, 0f, 360f, gap)
        ring.alpha = 255
        if (fraction > 0f) arc(c, 0f, 360f * fraction.coerceAtMost(1f), gap)
        if (icon != MeterIcon.NONE) {
            LedView.signPath(icon, cx, cy + r, iconH, path)
            sign.color = color
            c.drawPath(path, sign)
        }
    }

    /** Draws [from]..[to] degrees clockwise from the top, skipping [gap] degrees around the bottom. */
    private fun arc(c: Canvas, from: Float, to: Float, gap: Float) {
        val g0 = 180f - gap / 2f
        val g1 = 180f + gap / 2f
        if (gap <= 0f || to <= g0 || from >= g1) return draw(c, from, to)
        if (from < g0) draw(c, from, g0)
        if (to > g1) draw(c, g1, to)
    }

    private fun draw(c: Canvas, from: Float, to: Float) {
        if (to - from > 0.5f) c.drawArc(box, from - 90f, to - from, false, ring)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val def = (72 * d).toInt()
        setMeasuredDimension(resolveSize(def, widthMeasureSpec), resolveSize(def, heightMeasureSpec))
    }
}

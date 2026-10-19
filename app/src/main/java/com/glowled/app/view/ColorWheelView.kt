package com.glowled.app.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * A ring of every hue: put a finger on it and turn to pick the colour. The middle shows the
 * colour you get (with the saturation / brightness set elsewhere). Red is at the top.
 */
class ColorWheelView(context: Context) : View(context) {

    /** Called while turning, with the new hue (0..360). */
    var onHueChanged: ((Float) -> Unit)? = null

    var hue = 0f
        set(v) {
            field = ((v % 360f) + 360f) % 360f
            invalidate()
        }

    /** Colour shown in the middle (the full result, not just the hue). */
    var centerColor = Color.RED
        set(v) {
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val ringWidth = 26 * d
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ringWidth }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
        color = Color.WHITE
    }
    private var lastTick = -1

    init {
        contentDescription = context.getString(com.glowled.app.R.string.color_wheel)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Hues in order around the ring, starting with red at the top (the canvas is rotated -90°).
        val hues = IntArray(13) { Color.HSVToColor(floatArrayOf(it * 30f, 1f, 1f)) }
        ringPaint.shader = SweepGradient(w / 2f, h / 2f, hues, null)
    }

    private val radius get() = min(width, height) / 2f - ringWidth / 2f - 4 * d

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.save()
        canvas.rotate(-90f, cx, cy)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.restore()
        // The colour you get, in the middle.
        fill.color = centerColor
        canvas.drawCircle(cx, cy, radius - ringWidth / 2f - 12 * d, fill)
        // Thumb on the ring at the current hue.
        val a = (hue - 90f) * PI.toFloat() / 180f
        val tx = cx + radius * cos(a)
        val ty = cy + radius * sin(a)
        fill.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        canvas.drawCircle(tx, ty, ringWidth / 2f + 3 * d, fill)
        canvas.drawCircle(tx, ty, ringWidth / 2f + 3 * d, thumbStroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val dx = e.x - width / 2f
        val dy = e.y - height / 2f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only grab touches on (or near) the ring, so the dialog still scrolls elsewhere.
                if (hypot(dx, dy) < radius - ringWidth * 1.5f) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                update(dx, dy)
            }
            MotionEvent.ACTION_MOVE -> update(dx, dy)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun update(dx: Float, dy: Float) {
        // atan2 measures from 3 o'clock; the ring starts (red) at 12 o'clock.
        val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        hue = deg
        val tick = (hue / 15f).toInt()
        if (tick != lastTick) {
            lastTick = tick
            com.glowled.app.ui.Haptics.level(this, 0.55f)
        }
        onHueChanged?.invoke(hue)
    }
}

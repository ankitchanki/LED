package com.glowled.app.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Point
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.glowled.app.core.CutoutGeometry
import com.glowled.app.core.CutoutShape
import com.glowled.app.core.Geometry
import com.glowled.app.core.LedStyle
import com.glowled.app.core.MultiMode
import com.glowled.app.core.Prefs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws the notification light around the camera cutout (or along the screen edge).
 *
 * Battery notes:
 *  - nothing is allocated in [onDraw];
 *  - static styles never redraw, blink only redraws at on/off transitions;
 *  - animated styles respect an FPS cap instead of running at the panel's 90/120 Hz;
 *  - redraws stop as soon as the view is hidden or detached.
 */
class LedView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    // Configuration
    var style = LedStyle.PULSE
        private set
    private var multiMode = MultiMode.CYCLE
    private var colors = intArrayOf(Prefs.DEFAULT_COLOR)
    private var geometry: Geometry? = null
    private var thickness = 3 * density
    private var gap = 2 * density
    private var glow = true
    private var alphaMul = 1f
    private var periodScale = 1f
    private var fpsCap = 30
    private var cornerRadius = 40 * density

    /**
     * When true (overlay / AOD) the geometry is in absolute screen pixels. When false (in-app
     * previews) it is relative to this view, so scrolling or collapsing headers never shift it.
     */
    var useScreenCoords = true
        set(v) { field = v; dirty = true; invalidate() }

    /** Pixel offset applied by burn-in protection. */
    var shiftX = 0f
    var shiftY = 0f

    // Drawing state (pre-allocated)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shapePath = Path()
    private val ripplePath = Path()
    private val segPath = Path()
    private val measure = PathMeasure()
    private var pathLength = 0f
    private val rect = RectF()
    private val shaderMatrix = Matrix()
    private var sweep: SweepGradient? = null
    private val loc = IntArray(2)
    private val screen = Point()
    private var cx = 0f
    private var cy = 0f
    private var startTime = SystemClock.uptimeMillis()
    private var dirty = true

    // No offscreen layer: shadow layers (glow) render directly on the hardware canvas on API 28+,
    // and a full-screen layer would cost ~10 MB plus an extra composition pass every frame.

    fun configure(
        prefs: Prefs,
        geometry: Geometry,
        colors: IntArray,
        styleOverride: LedStyle? = null,
        gapDp: Int = prefs.gapDp,
        thicknessDp: Int = prefs.thicknessDp,
    ) {
        this.style = styleOverride ?: prefs.style
        this.multiMode = prefs.multiMode
        this.geometry = geometry
        this.thickness = thicknessDp * density
        this.gap = gapDp * density
        this.glow = prefs.glow
        this.alphaMul = prefs.ledAlpha / 100f
        this.periodScale = 1.5f.pow((5 - prefs.speed) / 2f)
        this.fpsCap = prefs.fpsCap
        this.cornerRadius = CutoutGeometry.cornerRadius(context, prefs)
        setColors(colors)
        dirty = true
        invalidate()
    }

    fun setColors(c: IntArray) {
        colors = if (c.isEmpty()) intArrayOf(Prefs.DEFAULT_COLOR) else c
        invalidate()
    }

    fun restartAnimation() {
        startTime = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Space the indicator needs around the cutout centre, for sizing small overlay windows. */
    fun extentPx(g: Geometry): Int {
        val half = max(g.w, g.h) / 2f + gap + thickness
        return (half + thickness * 7 + glowRadius() * 2 + 4 * density).toInt()
    }

    private fun glowRadius() = if (glow) thickness * 1.6f + 3 * density else 0f

    /** Lowest point of the drawn ring, in the geometry's coordinates (to place content under it). */
    fun ringBottom(): Float {
        val g = geometry ?: return 0f
        val r = if (g.shape == CutoutShape.DOT) max(g.w, g.h) / 2f else g.h / 2f + gap + thickness
        return g.cy + r + glowRadius()
    }

    /**
     * Entrance progress 0..1: the ring grows out of the camera hole and fades in, so it never
     * pops onto the screen at full size.
     */
    var intro = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        dirty = true
    }

    private fun rebuild() {
        dirty = false
        if (useScreenCoords) getLocationOnScreen(loc) else { loc[0] = 0; loc[1] = 0 }
        val g = geometry ?: return
        cx = g.cx - loc[0]
        cy = g.cy - loc[1]
        shapePath.reset()
        if (style.isEdge) {
            if (useScreenCoords) {
                @Suppress("DEPRECATION")
                display?.getRealSize(screen) ?: CutoutGeometry.display(context).getRealSize(screen)
            } else {
                screen.set(width, height)
            }
            val inset = thickness / 2f + 0.5f + (if (useScreenCoords) 0f else glowRadius())
            rect.set(inset - loc[0], inset - loc[1], screen.x - inset - loc[0], screen.y - inset - loc[1])
            val maxR = if (useScreenCoords) cornerRadius else min(cornerRadius, min(width, height) / 4f)
            val r = max(0f, maxR - inset)
            shapePath.addRoundRect(rect, r, r, Path.Direction.CW)
        } else {
            buildShape(shapePath, 0f)
        }
        measure.setPath(shapePath, true)
        pathLength = measure.length
        if (style == LedStyle.RAINBOW) {
            sweep = SweepGradient(
                cx, cy,
                intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED),
                null,
            )
        } else {
            sweep = null
        }
    }

    /** Adds the ring outline for the current shape, grown outwards by [inflate] px. */
    private fun buildShape(p: Path, inflate: Float) {
        val g = geometry ?: return
        val isDot = g.shape == CutoutShape.DOT
        val off = if (isDot) inflate else gap + thickness / 2f + inflate
        val hw = g.w / 2f + off
        val hh = g.h / 2f + off
        val x = cx
        val y = cy
        when (g.shape) {
            CutoutShape.CIRCLE, CutoutShape.DOT -> p.addCircle(x, y, max(hw, hh), Path.Direction.CW)
            CutoutShape.PILL -> {
                rect.set(x - hw, y - hh, x + hw, y + hh)
                p.addRoundRect(rect, hh, hh, Path.Direction.CW)
            }
            CutoutShape.NOTCH -> {
                rect.set(x - hw, y - hh, x + hw, y + hh)
                val r = min(hh, 18 * density + off)
                p.addRoundRect(rect, r, r, Path.Direction.CW)
            }
        }
    }

    private val isFilled get() = geometry?.shape == CutoutShape.DOT && !style.isEdge

    override fun onDraw(canvas: Canvas) {
        if (dirty) rebuild()
        if (geometry == null || pathLength <= 0f) return

        val now = SystemClock.uptimeMillis()
        val period = max(200L, (basePeriod() * periodScale).toLong())
        val elapsed = now - startTime
        val cycle = elapsed / period
        val inPeriod = elapsed % period
        val p = inPeriod / period.toFloat()
        val n = colors.size
        val segmented = multiMode == MultiMode.SEGMENTS && n > 1
        val color = if (multiMode == MultiMode.LATEST) colors[0] else colors[(cycle % n).toInt()]

        val transformed = shiftX != 0f || shiftY != 0f || intro < 1f
        if (transformed) {
            canvas.save()
            // Burn-in protection: move the whole drawing by a few pixels.
            canvas.translate(shiftX, shiftY)
            if (intro < 1f && !style.isEdge) {
                val s = 0.6f + 0.4f * intro
                canvas.scale(s, s, cx, cy)
            }
        }

        when (style) {
            LedStyle.STATIC -> ring(canvas, color, 1f, segmented, 0f)
            LedStyle.BREATHE -> ring(canvas, color, 0.12f + 0.88f * wave(p), segmented, 0f)
            LedStyle.EDGE_PULSE -> ring(canvas, color, 0.1f + 0.9f * wave(p), segmented, 0f)
            LedStyle.BLINK -> if (p < BLINK_ON) ring(canvas, color, 1f, segmented, 0f)
            LedStyle.HEARTBEAT -> ring(canvas, color, 0.08f + 0.92f * heartbeat(p), segmented, 0f)
            LedStyle.PULSE -> {
                ring(canvas, color, 0.55f + 0.45f * (1f - wave(p)), segmented, 0f)
                ripple(canvas, color, p)
            }
            LedStyle.SPIN -> spin(canvas, p, segmented, color, tail = 0.38f, comets = 1, base = 0.16f)
            LedStyle.EDGE -> spin(canvas, p, segmented, color, tail = 0.16f, comets = 2, base = 0.06f)
            LedStyle.RAINBOW -> rainbow(canvas, p)
        }

        if (transformed) canvas.restore()
        scheduleNext(period, inPeriod, n, segmented)
    }

    private fun basePeriod(): Long = when (style) {
        LedStyle.STATIC -> 2500
        LedStyle.BREATHE -> 3200
        LedStyle.PULSE -> 2000
        LedStyle.BLINK -> 2400
        LedStyle.HEARTBEAT -> 1700
        LedStyle.SPIN -> 1500
        LedStyle.RAINBOW -> 3000
        LedStyle.EDGE -> 3200
        LedStyle.EDGE_PULSE -> 3000
    }

    private fun scheduleNext(period: Long, inPeriod: Long, n: Int, segmented: Boolean) {
        if (!isShown || windowVisibility != VISIBLE) return
        val cycling = n > 1 && !segmented && multiMode != MultiMode.LATEST
        when (style) {
            LedStyle.STATIC -> if (cycling) postInvalidateDelayed(period - inPeriod)
            LedStyle.BLINK -> {
                val onMs = (period * BLINK_ON).toLong()
                postInvalidateDelayed(if (inPeriod < onMs) onMs - inPeriod else period - inPeriod)
            }
            else -> if (fpsCap > 0) postInvalidateDelayed(1000L / fpsCap) else postInvalidateOnAnimation()
        }
    }

    // ---- primitives ---------------------------------------------------------------------------

    private fun paintFor(color: Int, a: Float, filled: Boolean): Paint {
        val paint = if (filled) fill else stroke
        paint.shader = null
        paint.color = color
        paint.alpha = (255 * (a * alphaMul * intro).coerceIn(0f, 1f)).toInt()
        stroke.strokeWidth = thickness
        if (glow && paint.alpha > 8) paint.setShadowLayer(glowRadius(), 0f, 0f, paint.color)
        else paint.clearShadowLayer()
        return paint
    }

    private fun ring(c: Canvas, color: Int, a: Float, segmented: Boolean, rotation: Float) {
        if (a <= 0.01f) return
        if (!segmented) {
            c.drawPath(shapePath, paintFor(color, a, isFilled))
            return
        }
        val n = colors.size
        if (isFilled) {
            val g = geometry ?: return
            val r = max(g.w, g.h) / 2f
            rect.set(cx - r, cy - r, cx + r, cy + r)
            val sweepDeg = 360f / n
            for (i in 0 until n) {
                c.drawArc(rect, -90f + i * sweepDeg + rotation * 360f, sweepDeg, true, paintFor(colors[i], a, true))
            }
            return
        }
        val gapFrac = min(0.04f, 0.25f / n)
        for (i in 0 until n) {
            val s = i / n.toFloat() + rotation + gapFrac / 2f
            segment(c, s, s + 1f / n - gapFrac, paintFor(colors[i], a, false))
        }
    }

    private fun ripple(c: Canvas, color: Int, p: Float) {
        val travel = thickness * 2f + 10 * density
        ripplePath.reset()
        buildShape(ripplePath, travel * p)
        val a = (1f - p).pow(1.6f) * 0.9f
        c.drawPath(ripplePath, paintFor(color, a, false))
    }

    private fun spin(c: Canvas, p: Float, segmented: Boolean, color: Int, tail: Float, comets: Int, base: Float) {
        val n = if (segmented) colors.size else comets
        if (base > 0f) {
            c.drawPath(shapePath, paintFor(colors[0], base, false).also { it.clearShadowLayer() })
        }
        for (i in 0 until n) {
            val col = if (segmented) colors[i] else color
            val head = p + i / n.toFloat()
            for (k in 0 until TAIL_SLICES) {
                val f0 = k / TAIL_SLICES.toFloat()
                val f1 = (k + 1) / TAIL_SLICES.toFloat()
                val a = (1f - f0).pow(1.7f)
                // Only the head carries the glow to keep the blur cost tiny.
                val paint = paintFor(col, a, false)
                if (k > 1) paint.clearShadowLayer()
                segment(c, head - tail * f1, head - tail * f0, paint)
            }
        }
    }

    private fun rainbow(c: Canvas, p: Float) {
        val s = sweep ?: return
        shaderMatrix.setRotate(p * 360f, cx, cy)
        s.setLocalMatrix(shaderMatrix)
        val paint = if (isFilled) fill else stroke
        paint.clearShadowLayer()
        paint.color = Color.WHITE
        paint.alpha = (255 * alphaMul * intro).toInt()
        stroke.strokeWidth = thickness
        paint.shader = s
        c.drawPath(shapePath, paint)
        paint.shader = null
    }

    /** Draws the part of the shape between path fractions [from]..[to] (wraps around). */
    private fun segment(c: Canvas, from: Float, to: Float, paint: Paint) {
        val a = from - kotlin.math.floor(from)
        val b = a + (to - from)
        if (b - a <= 0f) return
        if (b > 1f) {
            segPath.reset()
            measure.getSegment(a * pathLength, pathLength, segPath, true)
            measure.getSegment(0f, (b - 1f) * pathLength, segPath, true)
        } else {
            segPath.reset()
            measure.getSegment(a * pathLength, b * pathLength, segPath, true)
        }
        c.drawPath(segPath, paint)
    }

    private fun wave(p: Float) = (0.5f - 0.5f * cos(2f * PI.toFloat() * p))

    private fun heartbeat(p: Float): Float {
        fun bump(s: Float, e: Float) = if (p in s..e) sin(PI.toFloat() * (p - s) / (e - s)) else 0f
        return max(bump(0f, 0.14f), bump(0.22f, 0.38f) * 0.8f)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    companion object {
        private const val BLINK_ON = 0.12f
        private const val TAIL_SLICES = 9
    }
}

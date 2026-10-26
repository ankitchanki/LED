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
import com.glowled.app.core.MeterIcon
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
    /** Slowly rotate a ring that is split into app colours. */
    private var rotateRing = true
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
        meterSettled = false
        this.style = styleOverride ?: prefs.style
        this.multiMode = prefs.multiMode
        this.geometry = geometry
        this.thickness = thicknessDp * density
        this.gap = gapDp * density
        this.glow = prefs.glow
        this.alphaMul = prefs.ledAlpha / 100f
        this.periodScale = 1.5f.pow((5 - prefs.speed) / 2f)
        // The fps cap saves battery on the always-on screen; in-app previews run at the display's rate.
        this.fpsCap = if (uncapped) 0 else prefs.fpsCap
        this.rotateRing = prefs.rotateRing
        this.cornerRadius = CutoutGeometry.cornerRadius(context, prefs)
        setColors(colors)
        dirty = true
        invalidate()
    }

    fun setColors(c: IntArray) {
        colors = if (c.isEmpty()) intArrayOf(Prefs.DEFAULT_COLOR) else c
        invalidate()
    }

    // ---- gauge: battery level while charging, or download progress -----------------------------

    /** Fill level 0..1 of the gauge, or -1 for the normal notification light. */
    private var meterFrac = -1f
    var notificationRingVisible = true
        set(value) { if (field != value) { field = value; invalidate() } }
    private var meterIcon = MeterIcon.NONE
    /** When the charging bolt last flared up (a new battery percent); -1 = not pulsing. */
    private var iconPulseStart = -1L
    private val iconPath = Path()
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var meterIndeterminate = false
    private var meterSettled = false
    private var batteryLabel: String? = null
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10 * resources.displayMetrics.scaledDensity
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var meterColor = 0
    private var meterStart = 0L
    /** Where on the outline its top-centre point is (0..1), so the gauge fills from 12 o'clock. */
    private var topFrac = 0f
    private val posTmp = FloatArray(2)

    /**
     * Shows the ring as a gauge filled clockwise from the top: [fraction] 0..1 in [color], over a
     * faint full track. Null goes back to the normal light. The fill sweeps in when it appears and a
     * soft highlight flows along it, so it reads as "in progress".
     */
    fun setMeter(fraction: Float?, color: Int, icon: MeterIcon = MeterIcon.NONE, indeterminate: Boolean = false) {
        val f = fraction?.coerceIn(0f, 1f) ?: -1f
        if (f == meterFrac && color == meterColor && icon == meterIcon && indeterminate == meterIndeterminate) return
        val now = SystemClock.uptimeMillis()
        if (f >= 0f && meterFrac < 0f) meterStart = now
        // Charging: the bolt flares up when it appears and each time the battery gains a percent.
        if (icon == MeterIcon.BOLT && f >= 0f && (meterIcon != MeterIcon.BOLT || f > meterFrac + 0.004f)) iconPulseStart = now
        meterFrac = f
        meterColor = color
        meterIcon = icon
        meterIndeterminate = indeterminate
        invalidate()
    }

    fun setBattery(percent: Int?) {
        val text = percent?.coerceIn(0, 100)?.let { "$it%" }
        if (batteryLabel == text) return
        batteryLabel = text
        invalidate()
    }

    private val indicatorLineHeight get() = max(16 * density, labelPaint.fontSpacing + 2 * density)

    /** Leave space for the battery % without overlapping notification icons. */
    fun indicatorBottom(): Float = ringBottom() + (if (batteryLabel != null) indicatorLineHeight else 0f)

    private fun drawLabels(c: Canvas) {
        val y = ringBottom() - (geometry?.cy ?: cy) + cy + indicatorLineHeight - labelPaint.descent()
        batteryLabel?.let {
            labelPaint.color = Color.WHITE
            labelPaint.alpha = (220 * alphaMul * intro).toInt()
            c.drawText(it, cx, y, labelPaint)
        }
    }

    private fun findTopFraction(): Float {
        if (pathLength <= 0f) return 0f
        var best = Float.MAX_VALUE
        var bestFrac = 0f
        for (i in 0 until TOP_SAMPLES) {
            val f = i / TOP_SAMPLES.toFloat()
            measure.getPosTan(f * pathLength, posTmp, null)
            // Highest point; on a flat top edge, the one closest to the centre.
            val score = posTmp[1] * 1000f + kotlin.math.abs(posTmp[0] - cx)
            if (score < best) {
                best = score
                bestFrac = f
            }
        }
        return bestFrac
    }

    private fun drawMeter(c: Canvas, now: Long) {
        val t = now - meterStart
        if (meterIndeterminate) {
            spin(c, (t % METER_FLOW_MS) / METER_FLOW_MS.toFloat(), false, meterColor,
                tail = 0.24f, comets = 1, base = METER_TRACK)
            return
        }
        val frac = if (meterSettled) meterFrac else meterFrac * easeOut((t / METER_SWEEP_MS.toFloat()).coerceIn(0f, 1f))
        if (isFilled) {
            val g = geometry ?: return
            val r = max(g.w, g.h) / 2f
            rect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect, 0f, 360f, true, paintFor(meterColor, METER_TRACK, true).also { it.clearShadowLayer() })
            c.drawArc(rect, -90f, 360f * frac, true, paintFor(meterColor, 1f, true))
            return
        }
        // With a sign, the ring leaves a small gap for it at its bottom middle.
        segmentGap(c, topFrac, topFrac + 1f, paintFor(meterColor, METER_TRACK, false).also { it.clearShadowLayer() })
        if (frac > 0.002f) segmentGap(c, topFrac, topFrac + frac, paintFor(meterColor, 1f, false))
        if (frac > 0.05f && !meterSettled) {
            val p = (t % METER_FLOW_MS) / METER_FLOW_MS.toFloat()
            val head = topFrac + frac * p
            segmentGap(c, head - 0.04f, head, paintFor(Color.WHITE, 0.5f, false).also { it.clearShadowLayer() })
        }
        drawMeterIcon(c, now)
    }

    private val iconSize get() = max(8f * density, thickness * 2.6f)

    /** Half the gap (as a fraction of the outline) the ring leaves around the sign. */
    private fun iconGapHalf(): Float =
        if (meterIcon == MeterIcon.NONE || pathLength <= 0f) 0f else (iconSize * 0.62f + 2.5f * density) / pathLength

    /** Draws [from]..[to] of the outline, leaving out the gap around the sign at the bottom middle. */
    private fun segmentGap(c: Canvas, from: Float, to: Float, paint: Paint) {
        val h = iconGapHalf()
        if (h <= 0f) return segment(c, from, to, paint)
        val bottom = topFrac + 0.5f
        var start = from
        // The gap repeats every full turn; check the turns this stretch can touch.
        for (k in -1..2) {
            val g0 = bottom + k - h
            val g1 = bottom + k + h
            if (g1 <= start || g0 >= to) continue
            if (g0 > start) segment(c, start, g0, paint)
            start = max(start, g1)
        }
        if (start < to) segment(c, start, to, paint)
    }

    /**
     * The sign on the ring's bottom middle: a charging bolt (which flares up for a moment on every
     * new battery percent) or a transfer arrow. On a filled LED dot it sits in the middle, in black.
     */
    private fun drawMeterIcon(c: Canvas, now: Long) {
        if (meterIcon == MeterIcon.NONE) return
        val bx: Float
        val by: Float
        if (isFilled) {
            bx = cx; by = cy
        } else {
            measure.getPosTan(((topFrac + 0.5f) % 1f) * pathLength, posTmp, null)
            bx = posTmp[0]; by = posTmp[1]
        }
        val pt = if (iconPulseStart < 0) 1f else ((now - iconPulseStart) / ICON_PULSE_MS.toFloat())
        val bump = if (pt < 1f) sin(PI.toFloat() * pt) else 0f
        val size = iconSize * (1f + 0.3f * bump)
        buildIcon(meterIcon, bx, by, size)
        val base = if (isFilled) Color.BLACK else meterColor
        // Flare: brighter (towards white) with a stronger glow, then back.
        iconPaint.color = if (bump > 0f) blend(base, Color.WHITE, 0.55f * bump) else base
        iconPaint.alpha = (255 * alphaMul * intro).toInt().coerceIn(0, 255)
        val glowR = (if (glow) glowRadius() * 0.6f else 0f) + 6f * density * bump
        // The halo keeps the ring's colour (a whitened halo looks grey on black).
        if (glowR > 0.5f && !isFilled) iconPaint.setShadowLayer(glowR, 0f, 0f, base) else iconPaint.clearShadowLayer()
        c.drawPath(iconPath, iconPaint)
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    /** Fills [iconPath] with [icon] centred on (x, y), [h] tall. */
    private fun buildIcon(icon: MeterIcon, x: Float, y: Float, h: Float) = signPath(icon, x, y, h, iconPath)

    /** In-app previews: ignore the battery frame-rate cap and animate at the display's refresh rate. */
    var uncapped = false

    /** 1 = full intensity; lower for notifications that have been waiting a while. */
    var ageFade = 1f
        set(v) {
            if (field != v) { field = v; invalidate() }
        }

    /**
     * Eco: stop animating and hold a still ring, so nothing is redrawn until something changes.
     * Edge styles keep moving, at a low frame rate. [configure] brings the animation back.
     */
    fun settle() {
        meterSettled = true
        if (style.isEdge) fpsCap = 10 else style = LedStyle.STATIC
        rotateRing = false
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

    // ---- droplet: liquid drop that stretches out of the camera, detaches, falls and splashes ------

    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val splashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dropPath = Path()
    private var dropAnim: android.animation.ValueAnimator? = null
    private var dropT = -1f
    private var dropColor = 0
    private var dropTargetY = 0f
    private var dropTargetX = Float.NaN // NaN = straight below the camera
    private var dropRetract = false

    /**
     * Plays the droplet in [color] from the bottom of the ring down to [targetY] (view coordinates).
     * [retract] = stretch down and spring back into the camera instead of falling (small windows).
     * [targetX] lets the drop curve sideways onto a specific icon (NaN = straight down).
     * [onLanded] fires when the drop hits its target (e.g. to pop the app icon out of it).
     */
    fun playDroplet(
        color: Int,
        targetY: Float,
        retract: Boolean = false,
        targetX: Float = Float.NaN,
        onLanded: (() -> Unit)? = null,
    ) {
        if (dirty) rebuild()
        if (geometry == null) {
            onLanded?.invoke()
            return
        }
        dropAnim?.cancel()
        dropColor = color or 0xFF000000.toInt()
        dropTargetY = targetY
        dropTargetX = targetX
        dropRetract = retract
        var landed = false
        dropAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (retract) 1000 else 1500
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                dropT = it.animatedValue as Float
                if (!landed && !retract && dropT >= LAND_AT) {
                    landed = true
                    onLanded?.invoke()
                }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    dropT = -1f
                    if (!landed) onLanded?.invoke()
                    invalidate()
                }
            })
            start()
        }
    }

    /** Droplet that drips [distancePx] below the ring (view-local), e.g. springing back on screen-on. */
    fun playDropletBelow(color: Int, distancePx: Float, retract: Boolean) {
        if (dirty) rebuild()
        playDroplet(color, cy + ringRadius() + distancePx, retract)
    }

    private fun ringRadius(): Float {
        val g = geometry ?: return 0f
        return if (g.shape == CutoutShape.DOT) max(g.w, g.h) / 2f else g.h / 2f + gap + thickness / 2f
    }

    private fun drawDroplet(c: Canvas) {
        val t = dropT
        if (t < 0f) return
        val ringR = ringRadius()
        val x = cx
        val top = cy + ringR // bottom edge of the ring: where the liquid comes out
        val dist = max(dropTargetY - top, ringR * 2f)
        // Big enough to read as a drop on a real phone (~5-7 dp radius).
        val rb = max(max(thickness * 2.2f, ringR * 0.45f), 5f * density)
        dropPaint.color = dropColor
        if (glow) dropPaint.setShadowLayer(glowRadius(), 0f, 0f, dropColor) else dropPaint.clearShadowLayer()

        if (dropRetract) {
            // Stretch down with an elastic neck, then spring back into the camera.
            val s = if (t < 0.5f) easeOut(t / 0.5f) else 1f - easeInOutBack((t - 0.5f) / 0.5f)
            val by = top + rb * 0.4f + (dist - rb) * s
            neck(c, x, top, ringR * 0.55f, by, rb * (0.7f + 0.3f * s), 1f - s * 0.7f)
            c.drawCircle(x, by, rb * (0.7f + 0.3f * s), dropPaint)
            return
        }

        when {
            t < DETACH_AT -> {
                // Bulge grows out of the ring and stretches; the neck thins as it pulls away.
                val p = easeInOut(t / DETACH_AT)
                val by = top + rb * 0.3f + dist * 0.42f * p
                val r = rb * (0.45f + 0.55f * p)
                neck(c, x, top, ringR * 0.6f, by, r, 1f - p * 0.85f)
                c.drawCircle(x, by, r, dropPaint)
            }
            t < LAND_AT -> {
                // Detached: falls with gravity; a little blob snaps back into the camera.
                val p = (t - DETACH_AT) / (LAND_AT - DETACH_AT)
                val start = top + rb * 0.3f + dist * 0.42f
                val by = start + (dropTargetY - start) * (p * p)
                // Drift sideways onto the target icon while falling.
                val bx = x + (landX() - x) * easeInOut(p)
                val stretch = 1f + 0.25f * p
                c.save()
                c.scale(1f / stretch, stretch, bx, by)
                c.drawCircle(bx, by, rb, dropPaint)
                c.restore()
                val back = 1f - easeOut(min(1f, p * 2.5f))
                if (back > 0.01f) c.drawCircle(x, top + rb * 0.2f * back, rb * 0.5f * back, dropPaint)
            }
            else -> {
                // Splash: the drop flattens and fades while a ripple spreads out.
                val p = (t - LAND_AT) / (1f - LAND_AT)
                val a = (1f - p)
                dropPaint.alpha = (255 * a).toInt()
                c.save()
                val lx = landX()
                c.scale(1f + 0.8f * p, 1f - 0.6f * p, lx, dropTargetY)
                c.drawCircle(lx, dropTargetY, rb, dropPaint)
                c.restore()
                splashPaint.color = dropColor
                splashPaint.alpha = (200 * a * a).toInt()
                splashPaint.strokeWidth = max(1.5f * density, thickness * 0.8f * a)
                c.drawCircle(lx, dropTargetY, rb * (1.2f + 3.2f * easeOut(p)), splashPaint)
            }
        }
    }

    private fun landX() = if (dropTargetX.isNaN()) cx else dropTargetX

    /** Gooey connection between the ring bottom (width [wa]) and a drop at [by] (radius [rb]). */
    private fun neck(c: Canvas, x: Float, ya: Float, wa: Float, by: Float, rb: Float, thick: Float) {
        val yb = by - rb * 0.5f
        if (yb <= ya) return
        val w = max(1f * density, min(wa, rb) * 0.55f * thick)
        val lb = rb * 0.85f
        val mid = (ya + yb) / 2f
        dropPath.reset()
        dropPath.moveTo(x - wa, ya - wa * 0.15f)
        dropPath.cubicTo(x - wa * 0.6f, ya + (mid - ya) * 0.4f, x - w, mid - (mid - ya) * 0.3f, x - w, mid)
        dropPath.cubicTo(x - w, mid + (yb - mid) * 0.4f, x - lb, yb - (yb - mid) * 0.5f, x - lb, yb)
        dropPath.lineTo(x + lb, yb)
        dropPath.cubicTo(x + lb, yb - (yb - mid) * 0.5f, x + w, mid + (yb - mid) * 0.4f, x + w, mid)
        dropPath.cubicTo(x + w, mid - (mid - ya) * 0.3f, x + wa * 0.6f, ya + (mid - ya) * 0.4f, x + wa, ya - wa * 0.15f)
        dropPath.close()
        c.drawPath(dropPath, dropPaint)
    }

    private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x)
    private fun easeInOut(x: Float) = if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f).pow(2) / 2f
    private fun easeInOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c2 = c1 * 1.525f
        return if (x < 0.5f) ((2f * x).pow(2) * ((c2 + 1) * 2f * x - c2)) / 2f
        else ((2f * x - 2f).pow(2) * ((c2 + 1) * (x * 2f - 2f) + c2) + 2f) / 2f
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
        topFrac = findTopFraction()
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

        // Split-by-app ring turns slowly (one turn every ~9 s at normal speed).
        val spinSplit = segmented && rotateRing && !style.isEdge && style != LedStyle.BLINK
        val rot = if (spinSplit) ((elapsed % ROTATE_MS.scaled()) / ROTATE_MS.scaled().toFloat()) else 0f
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

        if (meterFrac >= 0f) drawMeter(canvas, now) else if (notificationRingVisible) when (style) {
            LedStyle.STATIC -> ring(canvas, color, 1f, segmented, rot)
            LedStyle.BREATHE -> ring(canvas, color, 0.12f + 0.88f * wave(p), segmented, rot)
            LedStyle.EDGE_PULSE -> ring(canvas, color, 0.1f + 0.9f * wave(p), segmented, rot)
            LedStyle.BLINK -> if (p < BLINK_ON) ring(canvas, color, 1f, segmented, rot)
            LedStyle.HEARTBEAT -> ring(canvas, color, 0.08f + 0.92f * heartbeat(p), segmented, rot)
            LedStyle.PULSE -> {
                ring(canvas, color, 0.55f + 0.45f * (1f - wave(p)), segmented, rot)
                ripple(canvas, color, p)
            }
            LedStyle.SPIN -> spin(canvas, p, segmented, color, tail = 0.38f, comets = 1, base = 0.16f)
            LedStyle.EDGE -> spin(canvas, p, segmented, color, tail = 0.16f, comets = 2, base = 0.06f)
            LedStyle.RAINBOW -> rainbow(canvas, p)
            LedStyle.ORBIT -> spin(canvas, p, segmented, color, tail = 0.22f, comets = 2, base = 0.08f)
            LedStyle.SPARKLE -> {
                for (i in 0 until 6) {
                    val a = 0.12f + 0.88f * wave((p + i / 6f) % 1f).pow(4)
                    val start = i / 6f + p * 0.1f
                    segment(canvas, start, start + 0.075f, paintFor(colors[i % n], a, false))
                }
            }
        }

        drawLabels(canvas)
        if (transformed) canvas.restore()
        if (dropT >= 0f && !style.isEdge) {
            if (shiftX != 0f || shiftY != 0f) {
                canvas.save(); canvas.translate(shiftX, shiftY); drawDroplet(canvas); canvas.restore()
            } else {
                drawDroplet(canvas)
            }
        }
        scheduleNext(period, inPeriod, n, segmented)
    }

    private fun Long.scaled() = max(1500L, (this * periodScale).toLong())

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
        LedStyle.ORBIT -> 2400
        LedStyle.SPARKLE -> 3600
    }

    private fun scheduleNext(period: Long, inPeriod: Long, n: Int, segmented: Boolean) {
        if (!isShown || windowVisibility != VISIBLE) return
        if (meterFrac >= 0f) {
            val pulsing = iconPulseStart >= 0 && SystemClock.uptimeMillis() - iconPulseStart < ICON_PULSE_MS
            if (!meterSettled || meterIndeterminate || pulsing) postInvalidateDelayed(METER_FRAME_MS)
            return
        }
        if (!notificationRingVisible) return
        val cycling = n > 1 && !segmented && multiMode != MultiMode.LATEST
        when (style) {
            LedStyle.STATIC -> when {
                segmented && rotateRing -> if (fpsCap > 0) postInvalidateDelayed(1000L / fpsCap) else postInvalidateOnAnimation()
                cycling -> postInvalidateDelayed(period - inPeriod)
            }
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
        paint.alpha = (255 * (a * alphaMul * ageFade * intro).coerceIn(0f, 1f)).toInt()
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
        paint.alpha = (255 * alphaMul * ageFade * intro).toInt()
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

    override fun onDetachedFromWindow() {
        dropAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    companion object {
        private const val TOP_SAMPLES = 180
        private const val METER_TRACK = 0.16f
        private const val METER_SWEEP_MS = 700L
        private const val METER_FLOW_MS = 2400L
        private const val METER_FRAME_MS = 50L
        /** How long the charging bolt flares up on a new battery percent. */
        private const val ICON_PULSE_MS = 1200L
        // Signs as polygons in a 1x1 box (x, y pairs).
        private val BOLT_PTS = floatArrayOf(0.64f, 0f, 0.08f, 0.58f, 0.46f, 0.58f, 0.34f, 1f, 0.92f, 0.4f, 0.54f, 0.4f)
        private val UP_PTS = floatArrayOf(0.5f, 0f, 1f, 0.5f, 0.68f, 0.5f, 0.68f, 1f, 0.32f, 1f, 0.32f, 0.5f, 0f, 0.5f)
        private val DOWN_PTS = floatArrayOf(0.5f, 1f, 1f, 0.5f, 0.68f, 0.5f, 0.68f, 0f, 0.32f, 0f, 0.32f, 0.5f, 0f, 0.5f)
        private val BOTH_PTS = floatArrayOf(
            0.5f, 0f, 0.95f, 0.36f, 0.64f, 0.36f, 0.64f, 0.64f, 0.95f, 0.64f, 0.5f, 1f,
            0.05f, 0.64f, 0.36f, 0.64f, 0.36f, 0.36f, 0.05f, 0.36f,
        )

        /** Fills [out] with the ring sign [icon] centred on (x, y), [h] tall (shared with in-app previews). */
        fun signPath(icon: MeterIcon, x: Float, y: Float, h: Float, out: Path) {
            out.reset()
            val pts = when (icon) {
                MeterIcon.BOLT -> BOLT_PTS
                MeterIcon.UP -> UP_PTS
                MeterIcon.DOWN -> DOWN_PTS
                MeterIcon.BOTH -> BOTH_PTS
                MeterIcon.NONE -> return
            }
            // Points are in a 1x1 box; signs are a bit narrower than tall.
            val w = h * 0.72f
            for (i in pts.indices step 2) {
                val px = x - w / 2f + pts[i] * w
                val py = y - h / 2f + pts[i + 1] * h
                if (i == 0) out.moveTo(px, py) else out.lineTo(px, py)
            }
            out.close()
        }

        private const val BLINK_ON = 0.12f
        private const val ROTATE_MS = 9_000L
        private const val DETACH_AT = 0.42f
        private const val LAND_AT = 0.74f
        private const val TAIL_SLICES = 9
    }
}

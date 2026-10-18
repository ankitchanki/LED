package com.glowled.app.core

import android.content.Context
import android.graphics.Point
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import kotlin.math.max
import kotlin.math.min

/** Camera hole / notch geometry in absolute display pixels (natural portrait orientation). */
data class Geometry(
    val shape: CutoutShape,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
)

object CutoutGeometry {

    /** Default display; DisplayManager can return null briefly (e.g. during display changes). */
    fun display(context: Context): Display =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: @Suppress("DEPRECATION") context.getSystemService(android.view.WindowManager::class.java).defaultDisplay

    /** Real (full) display size in the natural orientation. */
    fun naturalSize(context: Context): Point {
        val d = display(context)
        val p = Point()
        @Suppress("DEPRECATION")
        d.getRealSize(p)
        val rot = d.rotation
        return if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) Point(p.y, p.x) else p
    }

    fun isPortrait(context: Context): Boolean {
        val r = display(context).rotation
        return r == Surface.ROTATION_0
    }

    /** Detects the camera cutout from the system. Returns null if there is none or the device is rotated. */
    fun detect(context: Context): Geometry? {
        val d = display(context)
        if (d.rotation != Surface.ROTATION_0) return null
        val cutout = d.cutout ?: return null
        val screenW = naturalSize(context).x.toFloat()

        val bounds = RectF()
        var exact = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val path = cutout.cutoutPath
            if (path != null && !path.isEmpty) {
                path.computeBounds(bounds, true)
                exact = true
            }
        }
        if (!exact) {
            // Prefer the cutout on the top edge; fall back to the first one.
            val rects = cutout.boundingRects
            if (rects.isEmpty()) return null
            val r = rects.minByOrNull { it.top } ?: return null
            bounds.set(r)
        }
        if (bounds.width() <= 0f || bounds.height() <= 0f) return null

        var w = bounds.width()
        var h = bounds.height()
        var cx = bounds.centerX()
        var cy = bounds.centerY()
        val shape: CutoutShape
        when {
            w > screenW * 0.2f -> shape = CutoutShape.NOTCH
            w / h > 1.5f -> shape = CutoutShape.PILL
            else -> {
                shape = CutoutShape.CIRCLE
                if (!exact && bounds.top <= 0.5f && h > w * 1.15f) {
                    // Bounding rects of punch holes usually extend to the top edge.
                    h = w
                    cy = bounds.bottom - w / 2f
                }
                val dmt = min(w, h)
                w = dmt; h = dmt
            }
        }
        if (shape == CutoutShape.NOTCH && bounds.top <= 0.5f) {
            // Keep a notch's top glued to the screen edge.
            cy = bounds.bottom - h / 2f
        }
        cx = cx.coerceIn(0f, screenW)
        return Geometry(shape, cx, cy, w, h)
    }

    /** Fallback when the device reports no cutout: a small LED dot near the top centre. */
    fun fallback(context: Context): Geometry {
        val size = naturalSize(context)
        val dp = context.resources.displayMetrics.density
        return Geometry(CutoutShape.DOT, size.x / 2f, 28 * dp, 12 * dp, 12 * dp)
    }

    /** Current geometry in current-resolution pixels, auto-detecting and saving on first use. */
    fun resolve(context: Context, prefs: Prefs): Geometry {
        val natural = naturalSize(context)
        if (!prefs.hasCutout) {
            val g = if (isPortrait(context)) detect(context) ?: fallback(context) else fallback(context)
            prefs.saveCutout(g.shape, g.cx, g.cy, g.w, g.h, natural.x)
            return g
        }
        // Devices like Samsung can switch resolution (FHD+/QHD+), so scale saved pixels.
        val s = natural.x.toFloat() / max(1, prefs.cutRefWidth)
        return Geometry(prefs.cutShape, prefs.cutCx * s, prefs.cutCy * s, prefs.cutW * s, prefs.cutH * s)
    }

    /** Screen corner radius in px for edge lighting (system value on Android 12+). */
    fun cornerRadius(context: Context, prefs: Prefs): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val rc = display(context).getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
            if (rc != null && rc.radius > 0) return rc.radius.toFloat()
        }
        return prefs.edgeRadiusDp * context.resources.displayMetrics.density
    }
}

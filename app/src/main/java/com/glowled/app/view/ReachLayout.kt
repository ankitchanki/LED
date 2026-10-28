package com.glowled.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * One-handed mode (like One UI "reachability"): pull down anywhere on the settings while the page
 * is scrolled to the top, and the tabs and settings slide down into thumb reach. Pull up (or press
 * back) to bring them back. The space opened above them shows [overlay], a view behind this layout.
 *
 * Smoothness: the settings only ever move (translation), they are never re-laid out while moving,
 * so every frame is cheap and nothing is cut off or blank while they slide either way. When the
 * motion ends, [onReachHeight] lets the pages add the same amount of bottom space, so their last
 * rows can still be scrolled into view while open.
 */
class ReachLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    /** The view revealed above the settings (behind this layout, sized and placed here). */
    var overlay: View? = null

    /** Where the revealed space starts (the bottom of the header). */
    var overlayTop: () -> Int = { 0 }

    /** True while the visible page can still scroll up (then a pull down scrolls it instead). */
    var canContentScrollUp: () -> Boolean = { false }

    /** Touches below this y (e.g. the bottom button dock) never start a pull. */
    var dragZoneBottom: () -> Int = { height }

    var onReachChanged: ((Boolean) -> Unit)? = null

    /** How far the settings are down after each motion (0 = closed). */
    var onReachHeight: ((Int) -> Unit)? = null

    /** A tap on the revealed space (it lies behind this layout, so taps are passed on from here). */
    var onOverlayTap: (() -> Unit)? = null

    /** The views below the header that slide with the finger (not the bottom dock). */
    var movable: List<View> = emptyList()

    /** Where the settings visually are (their translation). */
    private var shown = 0

    var isOpen = false
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val clip = Rect()
    private var downX = 0f
    private var downY = 0f
    private var startH = 0
    private var dragging = false
    private var pullingDown = false
    private var anim: ValueAnimator? = null

    /** How far the settings come down when open. */
    val reachHeight get() = maxReach

    /** How far the content comes down: about a third of the screen. */
    private val maxReach get() = (height * REACH_FRACTION).toInt()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val o = overlay ?: return
        // Sized once to the full reach; only its visible part (clip) changes while moving.
        post {
            if (o.layoutParams.height != maxReach) o.layoutParams = o.layoutParams.apply { height = maxReach }
            setReach(shown.coerceAtMost(maxReach))
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (overlay == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startH = shown
                dragging = false
                anim?.cancel()
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && downY < dragZoneBottom()) {
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.5f) {
                    if (dy > 0 && startH < maxReach && !canContentScrollUp()) {
                        dragging = true
                        pullingDown = true
                    } else if (dy < 0 && startH > 0) {
                        dragging = true
                        pullingDown = false
                    }
                    if (dragging) {
                        // Restart from here so the content doesn't jump by the slop distance.
                        downY = ev.y
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) {
                startH = shown
                settle()
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // Touch started on empty space (no child took it): detect the pull here too.
                if (!dragging) onInterceptTouchEvent(ev)
                if (dragging) setReach((startH + (ev.y - downY) * DRAG_RATIO).toInt().coerceIn(0, maxReach))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                val h = shown
                val open = if (pullingDown) h > maxReach * 0.22f else h > maxReach * 0.78f
                animateTo(open)
            } else {
                val top = overlayTop()
                if (ev.actionMasked == MotionEvent.ACTION_UP && shown > 0 && ev.y >= top && ev.y < top + shown) {
                    onOverlayTap?.invoke()
                }
                settle()
            }
        }
        return true
    }

    fun open() = animateTo(true)

    fun close() = animateTo(false)

    private fun animateTo(open: Boolean) {
        anim?.cancel()
        val target = if (open) maxReach else 0
        anim = ValueAnimator.ofInt(shown, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setReach(it.animatedValue as Int) }
            var cancelled = false
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!cancelled) onReachHeight?.invoke(target)
                }
            })
            start()
        }
        if (isOpen != open) {
            isOpen = open
            com.glowled.app.ui.Haptics.toggle(this, open)
            onReachChanged?.invoke(open)
        }
    }

    /**
     * A touch that didn't turn into a pull stopped a slide halfway: finish it, so the content
     * always ends fully open or fully closed.
     */
    private fun settle() {
        if (anim?.isRunning == true) return
        if (shown != if (isOpen) maxReach else 0) animateTo(isOpen)
    }

    /** Moves the settings to [h] and reveals that much of the space above them (no layout). */
    private fun setReach(h: Int) {
        shown = h
        movable.forEach { it.translationY = h.toFloat() }
        val o = overlay ?: return
        if (h <= 0) {
            o.visibility = View.INVISIBLE
            return
        }
        // Show the part of the space the settings have uncovered, anchored to its bottom edge, so it
        // slides in with them.
        val full = o.height.takeIf { it > 0 } ?: maxReach
        o.translationY = overlayTop().toFloat() - (full - h)
        clip.set(0, full - h, o.width, full)
        o.clipBounds = clip
        o.alpha = (h / maxReach.toFloat()).coerceIn(0f, 1f)
        o.visibility = View.VISIBLE
    }

    companion object {
        private const val REACH_FRACTION = 0.34f
        private const val DRAG_RATIO = 0.8f
    }
}

package com.glowled.app.view

import android.animation.ValueAnimator
import android.content.Context
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
 * back) to bring them back. The space opened above them is [spacer], a child of this layout whose
 * height is animated; the pages shrink instead of moving off-screen, so they stay scrollable.
 */
class ReachLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    /** The view that grows to push the content down. Must be a child with height 0. */
    var spacer: View? = null

    /** True while the visible page can still scroll up (then a pull down scrolls it instead). */
    var canContentScrollUp: () -> Boolean = { false }

    /** Touches below this y (e.g. the bottom button dock) never start a pull. */
    var dragZoneBottom: () -> Int = { height }

    var onReachChanged: ((Boolean) -> Unit)? = null

    var isOpen = false
        private set

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startH = 0
    private var dragging = false
    private var pullingDown = false
    private var anim: ValueAnimator? = null

    /** How far the content comes down: about a third of the screen. */
    private val maxReach get() = (height * REACH_FRACTION).toInt()

    private val current get() = spacer?.layoutParams?.height?.coerceAtLeast(0) ?: 0

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (spacer == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startH = current
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) startH = current
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
                val h = current
                val open = if (pullingDown) h > maxReach * 0.22f else h > maxReach * 0.78f
                animateTo(open)
            }
        }
        return true
    }

    fun open() = animateTo(true)

    fun close() = animateTo(false)

    private fun animateTo(open: Boolean) {
        anim?.cancel()
        val target = if (open) maxReach else 0
        anim = ValueAnimator.ofInt(current, target).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setReach(it.animatedValue as Int) }
            start()
        }
        if (isOpen != open) {
            isOpen = open
            performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
            onReachChanged?.invoke(open)
        }
    }

    private fun setReach(h: Int) {
        val s = spacer ?: return
        if (s.layoutParams.height == h) return
        s.layoutParams = s.layoutParams.apply { height = h }
        s.alpha = if (maxReach > 0) (h / maxReach.toFloat()).coerceIn(0f, 1f) else 0f
    }

    companion object {
        private const val REACH_FRACTION = 0.34f
        private const val DRAG_RATIO = 0.8f
    }
}

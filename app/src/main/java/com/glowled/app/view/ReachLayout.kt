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
 * back) to bring them back. The space opened above them is [spacer], a child of this layout.
 *
 * Smoothness: while dragging and animating only the [movable] views' translation changes (no
 * layout pass, so every frame is cheap even at 120 Hz). The spacer's height - and with it the
 * layout of the pages, which shrink so they stay scrollable - is set once, when the motion ends.
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

    /** The spacer's laid-out height after each motion (0 = closed), e.g. to pause what's inside it. */
    var onReachHeight: ((Int) -> Unit)? = null

    /** The views below the spacer that slide with the finger (not the bottom dock). */
    var movable: List<View> = emptyList()

    /** Height the spacer is laid out with; [shown] is where the content visually is. */
    private var committed = 0
    private var shown = 0

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

    private val current get() = shown

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
            var cancelled = false
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(a: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!cancelled) commit(target)
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

    /** Visual position only: slide the content (no layout). */
    private fun setReach(h: Int) {
        val s = spacer ?: return
        shown = h
        val dy = (h - committed).toFloat()
        movable.forEach { it.translationY = dy }
        if (committed > 0) s.alpha = if (maxReach > 0) (h / maxReach.toFloat()).coerceIn(0f, 1f) else 0f
    }

    /** The motion is over: lay the spacer out at [h] and drop the temporary translation. */
    private fun commit(h: Int) {
        val s = spacer ?: return
        val opening = committed == 0 && h > 0
        committed = h
        shown = h
        if (s.layoutParams.height != h) s.layoutParams = s.layoutParams.apply { height = h }
        movable.forEach { it.translationY = 0f }
        if (opening) {
            s.alpha = 0f
            s.animate().alpha(1f).setDuration(180).start()
        } else {
            s.alpha = if (h > 0) 1f else 0f
        }
        onReachHeight?.invoke(h)
    }

    companion object {
        private const val REACH_FRACTION = 0.34f
        private const val DRAG_RATIO = 0.8f
    }
}

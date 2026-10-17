package com.glowled.app.view

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.absoluteValue
import kotlin.math.sign

/**
 * Wraps a horizontally scrolling child placed inside a ViewPager2 so the child scrolls first and
 * the pager only swipes once the child reaches its edge (pattern from the ViewPager2 samples).
 */
class NestedScrollableHost(context: Context) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var initialX = 0f
    private var initialY = 0f

    private val parentPager: ViewPager2?
        get() {
            var v: View? = parent as? View
            while (v != null && v !is ViewPager2) v = v.parent as? View
            return v as? ViewPager2
        }

    private val child: View? get() = if (childCount > 0) getChildAt(0) else null

    private fun canChildScroll(delta: Float): Boolean {
        val direction = -delta.sign.toInt()
        return child?.canScrollHorizontally(direction) ?: false
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        handleIntercept(e)
        return super.onInterceptTouchEvent(e)
    }

    private fun handleIntercept(e: MotionEvent) {
        if (parentPager == null) return
        if (!canChildScroll(-1f) && !canChildScroll(1f)) return
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = e.x
                initialY = e.y
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - initialX
                val dy = e.y - initialY
                if (dx.absoluteValue > touchSlop || dy.absoluteValue > touchSlop) {
                    if (dy.absoluteValue > dx.absoluteValue) {
                        parent.requestDisallowInterceptTouchEvent(false)
                    } else {
                        parent.requestDisallowInterceptTouchEvent(canChildScroll(dx))
                    }
                }
            }
        }
    }
}

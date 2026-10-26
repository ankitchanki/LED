package com.glowled.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.animation.DecelerateInterpolator
import android.widget.CompoundButton
import androidx.core.graphics.ColorUtils
import com.glowled.app.R

/** A rounded on/off switch with a white knob that slides across, like the system switch on iPhone. */
class GlowSwitch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : CompoundButton(context, attrs) {

    private val d = resources.displayMetrics.density
    private val trackW = 51 * d
    private val trackH = 31 * d
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(2.5f * d, 0f, 1f * d, 0x40000000)
    }
    private val box = RectF()
    private val onColor = context.getColor(R.color.accent)
    private val offColor = context.getColor(R.color.switch_off)
    /** 0 = off, 1 = on; animated between. */
    private var pos = 0f
    private var anim: ValueAnimator? = null

    init {
        buttonDrawable = null
        background = null
        isClickable = true
        isFocusable = true
        pos = if (isChecked) 1f else 0f
    }

    override fun setChecked(checked: Boolean) {
        val was = isChecked
        super.setChecked(checked)
        // Called from the super constructor before this class's fields exist.
        @Suppress("SENSELESS_COMPARISON")
        if (track == null || was == checked) return
        val target = if (checked) 1f else 0f
        anim?.cancel()
        if (!isAttachedToWindow || !isLaidOut) {
            pos = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(pos, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        anim?.cancel()
        pos = if (isChecked) 1f else 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 48dp tall so the touch target is comfortable; the track is drawn in the middle.
        setMeasuredDimension(
            resolveSize((trackW + 4 * d).toInt(), widthMeasureSpec),
            resolveSize((48 * d).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val left = (width - trackW) / 2f
        val top = (height - trackH) / 2f
        box.set(left, top, left + trackW, top + trackH)
        track.color = ColorUtils.blendARGB(offColor, onColor, pos)
        if (!isEnabled) track.alpha = 110
        canvas.drawRoundRect(box, trackH / 2f, trackH / 2f, track)
        val r = trackH / 2f - 2 * d
        val cx = left + trackH / 2f + (trackW - trackH) * pos
        canvas.drawCircle(cx, top + trackH / 2f, r, knob)
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Switch::class.java.name
}

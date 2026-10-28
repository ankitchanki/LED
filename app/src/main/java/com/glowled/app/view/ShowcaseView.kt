package com.glowled.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.glowled.app.R
import com.glowled.app.core.CutoutShape
import com.glowled.app.core.Geometry
import com.glowled.app.core.LedController
import com.glowled.app.core.LedStyle
import com.glowled.app.core.MeterIcon
import com.glowled.app.core.NotificationStore

/**
 * "See it in action": a black card that plays each feature in turn (a new message, several apps,
 * charging, a download, an upload) with a label on top and a one-line "What is it?" below.
 * It changes by itself every few seconds; a tap moves on. Animates only while it is on screen.
 */
class ShowcaseView(context: Context, private val apps: List<NotificationStore.AppInfo>) : FrameLayout(context) {

    private enum class Scene(val title: Int, val about: Int) {
        NOTIFY(R.string.sc_notify, R.string.sc_notify_sub),
        MULTI(R.string.sc_multi, R.string.sc_multi_sub),
        CHARGE(R.string.sc_charge, R.string.sc_charge_sub),
        DOWNLOAD(R.string.sc_download, R.string.sc_download_sub),
        UPLOAD(R.string.sc_upload, R.string.sc_upload_sub),
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val prefs get() = LedController.prefs

    private val led = LedView(context).apply {
        useScreenCoords = false
        uncapped = true
    }
    private val icons = AppInfoView(context)
    private val title = TextView(context)
    private val about = TextView(context)
    private val dots = LinearLayout(context)

    private var scene = 0
    private var running = false
    private var anim: ValueAnimator? = null
    private val next = Runnable { show((scene + 1) % Scene.entries.size) }

    init {
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 22 * d
            setStroke(dp(1), context.getColor(R.color.glass_stroke))
        }
        clipToOutline = true
        // Camera hole the ring sits around.
        addView(View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF101014.toInt()) }
        }, LayoutParams(dp(HOLE_DP), dp(HOLE_DP), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = dp(CAM_DP - HOLE_DP / 2)
        })
        addView(led, LayoutParams(-1, -1))
        addView(icons, LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(CAM_DP + 40) })
        title.apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            letterSpacing = 0.02f
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = 12 * d }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(title, LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
        about.apply {
            textSize = 12f
            setTextColor(0xFFA6A6B2.toInt())
            maxLines = 3
            setLineSpacing(0f, 1.1f)
        }
        addView(about, LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(12)) })
        dots.orientation = LinearLayout.HORIZONTAL
        Scene.entries.forEach { _ ->
            dots.addView(View(context), LinearLayout.LayoutParams(dp(5), dp(5)).apply { marginStart = dp(4) })
        }
        addView(dots, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(20), dp(14), 0) })
        isClickable = true
        isFocusable = true
        setOnClickListener { next() }
    }

    /** Skips to the next feature. */
    fun next() {
        com.glowled.app.ui.Haptics.click(this)
        removeCallbacks(next)
        next.run()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0) post { show(scene) }
    }

    // Only animate while visible (activity in front, card on screen).
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible == running) return
        running = isVisible
        if (isVisible) post { show(scene) } else stop()
    }

    override fun onDetachedFromWindow() {
        running = false
        stop()
        super.onDetachedFromWindow()
    }

    private fun stop() {
        removeCallbacks(next)
        anim?.cancel()
    }

    private fun configure(colors: IntArray, style: LedStyle) {
        val hole = dp(HOLE_DP).toFloat()
        led.configure(prefs, Geometry(CutoutShape.CIRCLE, width / 2f, dp(CAM_DP).toFloat(), hole, hole), colors, style,
            thicknessDp = prefs.thicknessDp.coerceAtLeast(4))
    }

    private fun show(index: Int) {
        if (width == 0 || !running) return
        stop()
        scene = index
        val s = Scene.entries[index]
        title.setText(s.title)
        about.text = context.getString(R.string.what_is_it) + " " + context.getString(s.about)
        contentDescription = title.text.toString() + ". " + about.text
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == index) Color.WHITE else 0x55FFFFFF)
            }
        }
        val sample = apps.ifEmpty { listOf(NotificationStore.AppInfo("", 0xFF25D366.toInt(), 1)) }
        when (s) {
            Scene.NOTIFY -> {
                led.setMeter(null, 0)
                configure(intArrayOf(sample[0].color), prefs.style.takeIf { !it.isEdge } ?: LedStyle.PULSE)
                icons.monochrome = prefs.aodIcons
                icons.bind(sample.take(1))
                icons.visibility = View.VISIBLE
                entrance()
                led.post {
                    icons.prepareDrop()
                    led.playDroplet(sample[0].color, icons.top + icons.iconCenterY(), targetX = icons.left + icons.newestCenterX()) {
                        icons.popNewest()
                    }
                }
            }
            Scene.MULTI -> {
                led.setMeter(null, 0)
                // Three colours even when fewer sample apps are installed.
                val colors = (sample.map { it.color } + FALLBACK_COLORS).distinct().take(3)
                configure(colors.toIntArray(), LedStyle.PULSE)
                icons.monochrome = prefs.aodIcons
                icons.bind(sample.take(3))
                icons.visibility = View.VISIBLE
                entrance()
            }
            Scene.CHARGE -> {
                icons.visibility = View.INVISIBLE
                configure(intArrayOf(prefs.defaultColor), LedStyle.STATIC)
                // A few battery percents going up, one by one: the bolt flares on each.
                var level = 86
                led.setMeter(level / 100f, prefs.chargeColor(level), MeterIcon.BOLT)
                anim = ValueAnimator.ofInt(0, 4).apply {
                    duration = SCENE_MS - 400
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        val l = 86 + it.animatedValue as Int
                        if (l != level) {
                            level = l
                            led.setMeter(l / 100f, prefs.chargeColor(l), MeterIcon.BOLT)
                        }
                    }
                    start()
                }
            }
            Scene.DOWNLOAD, Scene.UPLOAD -> {
                icons.visibility = View.INVISIBLE
                configure(intArrayOf(prefs.defaultColor), LedStyle.STATIC)
                val color = if (s == Scene.DOWNLOAD) prefs.downloadColor else prefs.uploadColor
                val icon = if (s == Scene.DOWNLOAD) MeterIcon.DOWN else MeterIcon.UP
                anim = ValueAnimator.ofFloat(0.05f, 1f).apply {
                    duration = SCENE_MS - 300
                    interpolator = DecelerateInterpolator(1.2f)
                    addUpdateListener { led.setMeter(it.animatedValue as Float, color, icon) }
                    start()
                }
            }
        }
        postDelayed(next, SCENE_MS)
    }

    /** The ring grows out of the camera, as on the real screen. */
    private fun entrance() {
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { led.intro = it.animatedValue as Float }
            start()
        }
        led.restartAnimation()
    }

    companion object {
        private const val CAM_DP = 62
        private const val HOLE_DP = 26
        private const val SCENE_MS = 4200L
        private val FALLBACK_COLORS = listOf(0xFF25D366.toInt(), 0xFFE1306C.toInt(), 0xFF229ED9.toInt())
    }
}

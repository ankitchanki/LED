package com.glowled.app.view

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.glowled.app.core.NotificationStore

/**
 * One UI-style app strip under the camera on the always-on screen: plain icons only (no text). Icons are tappable ([onAppTap]) to open the peek card. The newest
 * icon can pop out of the droplet that falls from the camera ([prepareDrop] / [popFirst]).
 */
class AppInfoView(context: Context) : LinearLayout(context) {

    var onAppTap: ((String) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private val icons = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }
    private var shown = false
    private var selected: String? = null
    private val chips = mutableListOf<Pair<String, View>>()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        addView(icons, LayoutParams(-2, -2))
        alpha = 0f
        visibility = INVISIBLE
    }

    /** Vertical centre of the icon row, relative to this view (where the droplet should land). */
    fun iconCenterY(): Float = icons.top + dp(17f).toFloat()

    /** Horizontal centre of icon [index], relative to this view. */
    fun iconCenterX(index: Int = 0): Float {
        val chip = chips.getOrNull(index)?.second ?: return width / 2f
        return icons.left + chip.left + chip.width / 2f
    }

    fun bind(apps: List<NotificationStore.AppInfo>) {
        icons.removeAllViews()
        chips.clear()
        val pm = context.packageManager
        apps.take(MAX_ICONS).forEachIndexed { i, app ->
            val chip = iconChip(pm, app)
            chips += app.pkg to chip
            icons.addView(chip, LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(14f) })
        }
        val extra = apps.size - MAX_ICONS
        if (extra > 0) {
            icons.addView(TextView(context).apply {
                text = "+$extra"
                setTextColor(Color.argb(200, 255, 255, 255))
                textSize = 13f
                gravity = Gravity.CENTER
            }, LayoutParams(dp(34f), dp(34f)).apply { marginStart = dp(10f) })
        }
        if (selected != null && apps.none { it.pkg == selected }) selected = null
        applySelection()
    }

    /** Plain app icon (no frame, no dot); the ring segments already show each app's colour. */
    private fun iconChip(pm: PackageManager, app: NotificationStore.AppInfo): View {
        val size = dp(34f)
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            contentDescription = labelFor(pm, app.pkg)
            val icon = iconFor(pm, app.pkg)
            if (icon != null) {
                addView(ImageView(context).apply {
                    setImageDrawable(icon)
                    alpha = 0.92f // a touch dimmer than full brightness on a black panel
                }, LayoutParams(size, size))
            } else {
                addView(TextView(context).apply {
                    text = labelFor(pm, app.pkg).take(1).uppercase()
                    setTextColor(app.color)
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.argb(36, 255, 255, 255))
                    }
                }, LayoutParams(size, size))
            }
            setOnClickListener {
                selected = if (selected == app.pkg) null else app.pkg
                applySelection()
                onAppTap?.invoke(app.pkg)
            }
        }
    }

    /** The tapped app stays bright and lifts slightly; the others dim. */
    private fun applySelection() {
        chips.forEach { (pkg, v) ->
            val on = selected == null || selected == pkg
            v.animate().alpha(if (on) 1f else 0.45f).translationY(if (selected == pkg) -dp(3f).toFloat() else 0f)
                .setDuration(180).start()
        }
    }

    fun clearSelection() {
        selected = null
        applySelection()
    }

    /** Entrance: drop in with a slight overshoot; icons pop in one after another. */
    fun show() {
        if (icons.childCount == 0) return
        animate().cancel()
        visibility = VISIBLE
        if (!shown) {
            alpha = 0f
            translationY = -dp(14f).toFloat()
            scaleX = 0.85f
            scaleY = 0.85f
            for (i in 0 until icons.childCount) {
                icons.getChildAt(i).apply { alpha = 0f; scaleX = 0.3f; scaleY = 0.3f }
            }
        }
        shown = true
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(420).setInterpolator(OvershootInterpolator(1.3f)).start()
        for (i in 0 until icons.childCount) {
            icons.getChildAt(i).animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(60L + i * 60L).setDuration(320).setInterpolator(OvershootInterpolator(2f))
                .withEndAction { applySelection() }.start()
        }
    }

    /**
     * Before a droplet falls: show the strip with the newest icon hidden (it will pop out of the
     * drop). The other icons fade in meanwhile.
     */
    fun prepareDrop() {
        if (icons.childCount == 0) return
        animate().cancel()
        visibility = VISIBLE
        alpha = 1f; translationY = 0f; scaleX = 1f; scaleY = 1f
        for (i in 0 until icons.childCount) {
            val c = icons.getChildAt(i)
            c.animate().cancel()
            if (i == 0) {
                c.alpha = 0f; c.scaleX = 0.25f; c.scaleY = 0.25f
            } else if (!shown) {
                c.alpha = 0f; c.scaleX = 0.6f; c.scaleY = 0.6f
                c.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(150L + i * 60L).setDuration(300).start()
            }
        }
        shown = true
    }

    /** The drop has landed: the newest app's icon pops out of it. */
    fun popFirst() {
        val c = icons.getChildAt(0) ?: return
        c.animate().cancel()
        c.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(380)
            .setInterpolator(OvershootInterpolator(2.4f)).withEndAction { applySelection() }.start()
    }

    fun hide() {
        if (!shown) return
        shown = false
        animate().cancel()
        animate().alpha(0f).translationY(-dp(10f).toFloat()).scaleX(0.9f).scaleY(0.9f)
            .setDuration(280).setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!shown) visibility = INVISIBLE }
            .start()
    }

    companion object {
        private const val MAX_ICONS = 5
        private val labelCache = LruCache<String, String>(32)

        fun labelFor(pm: PackageManager, pkg: String): String = labelCache.get(pkg) ?: try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }.also { labelCache.put(pkg, it) }

        fun iconFor(pm: PackageManager, pkg: String) = try {
            pm.getApplicationIcon(pkg)
        } catch (_: Exception) {
            null
        }
    }
}

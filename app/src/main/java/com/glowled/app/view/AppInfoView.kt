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
 * One UI-style app strip shown under the camera ring on the always-on screen: up to four app
 * icons in coloured rings (+N for more) and the newest app's name. Drops in from the camera with
 * a slight overshoot and staggered icons; fades away after a few seconds to protect the panel.
 */
class AppInfoView(context: Context) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private val icons = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
    }
    private val label = TextView(context).apply {
        setTextColor(Color.argb(200, 255, 255, 255))
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        letterSpacing = 0.02f
    }
    private var shown = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        clipToPadding = false
        addView(icons, LayoutParams(-2, -2))
        addView(label, LayoutParams(-2, -2).apply { topMargin = dp(8f) })
        alpha = 0f
        visibility = INVISIBLE
    }

    /** Fills the strip from the current notifications (or [preview] sample data). */
    fun bind(apps: List<NotificationStore.AppInfo>) {
        icons.removeAllViews()
        val pm = context.packageManager
        val size = dp(34f)
        apps.take(MAX_ICONS).forEachIndexed { i, app ->
            icons.addView(iconChip(pm, app, size), LayoutParams(size, size).apply {
                if (i > 0) marginStart = dp(10f)
            })
        }
        val extra = apps.size - MAX_ICONS
        if (extra > 0) {
            icons.addView(TextView(context).apply {
                text = "+$extra"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.argb(40, 255, 255, 255))
                }
            }, LayoutParams(size, size).apply { marginStart = dp(10f) })
        }
        val first = apps.firstOrNull()
        label.text = when {
            first == null -> ""
            else -> {
                val name = labelFor(pm, first.pkg)
                val total = apps.sumOf { it.count }
                if (total > 1) "$name · $total notifications" else name
            }
        }
    }

    private fun iconChip(pm: PackageManager, app: NotificationStore.AppInfo, size: Int): View {
        val ring = (2 * d)
        return FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
                setStroke(ring.toInt().coerceAtLeast(2), app.color)
            }
            val icon = iconFor(pm, app.pkg)
            if (icon != null) {
                addView(ImageView(context).apply {
                    setImageDrawable(icon)
                    alpha = 0.92f // a touch dimmer than full brightness on a black panel
                }, FrameLayout.LayoutParams(size - dp(10f), size - dp(10f), Gravity.CENTER))
            } else {
                // No icon available: first letter of the app name in its LED colour.
                addView(TextView(context).apply {
                    text = labelFor(pm, app.pkg).take(1).uppercase()
                    setTextColor(app.color)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(-1, -1))
            }
        }
    }

    /** Entrance: drop from the camera with a slight overshoot; icons pop in one after another. */
    fun show() {
        if (icons.childCount == 0) return
        animate().cancel()
        visibility = VISIBLE
        if (!shown) {
            alpha = 0f
            translationY = -dp(18f).toFloat()
            scaleX = 0.82f
            scaleY = 0.82f
            for (i in 0 until icons.childCount) {
                icons.getChildAt(i).apply { alpha = 0f; scaleX = 0.4f; scaleY = 0.4f }
            }
        }
        shown = true
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(450).setInterpolator(OvershootInterpolator(1.3f)).start()
        for (i in 0 until icons.childCount) {
            icons.getChildAt(i).animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(120L + i * 60L).setDuration(320).setInterpolator(OvershootInterpolator(2f)).start()
        }
        label.alpha = 0f
        label.animate().alpha(1f).setStartDelay(220).setDuration(300).start()
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
        private const val MAX_ICONS = 4
        private val labelCache = LruCache<String, String>(32)

        private fun labelFor(pm: PackageManager, pkg: String): String = labelCache.get(pkg) ?: try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }.also { labelCache.put(pkg, it) }

        private fun iconFor(pm: PackageManager, pkg: String) = try {
            pm.getApplicationIcon(pkg)
        } catch (_: Exception) {
            null
        }
    }
}

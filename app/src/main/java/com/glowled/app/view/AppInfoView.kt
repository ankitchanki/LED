package com.glowled.app.view

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import com.glowled.app.core.NotificationStore

/**
 * One UI-style app strip under the camera on the always-on screen: app icons only - no text, no
 * dimming. The newest app sits in the middle, right under the camera (older ones alternate to its
 * left and right), so the droplet falls straight down into it ([prepareDrop] / [popNewest]).
 * Icons are tappable ([onAppTap]) to open the peek card.
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
    private val chips = LinkedHashMap<String, View>()
    private var newestPkg: String? = null

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
    fun iconCenterY(): Float = icons.top + dp(ICON_DP / 2f).toFloat()

    /** Horizontal centre of the newest app's icon, relative to this view. */
    fun newestCenterX(): Float {
        val chip = newestPkg?.let { chips[it] } ?: return width / 2f
        return icons.left + chip.left + chip.width / 2f
    }

    /** [apps] is newest first; the newest goes in the middle, older ones alternate outwards. */
    fun bind(apps: List<NotificationStore.AppInfo>) {
        icons.removeAllViews()
        chips.clear()
        val shownApps = apps.take(MAX_ICONS)
        newestPkg = shownApps.firstOrNull()?.pkg
        val pm = context.packageManager
        centreOut(shownApps).forEachIndexed { i, app ->
            val chip = iconChip(pm, app)
            chips[app.pkg] = chip
            icons.addView(chip, LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply { if (i > 0) marginStart = dp(16f) })
        }
        if (selected != null && shownApps.none { it.pkg == selected }) selected = null
        applySelection()
    }

    /** [n0, n1, n2, n3, n4] -> [n3, n1, n0, n2, n4]: newest in the centre. */
    private fun <T> centreOut(list: List<T>): List<T> {
        val out = ArrayDeque<T>()
        list.forEachIndexed { i, v -> if (i == 0) out.add(v) else if (i % 2 == 1) out.addFirst(v) else out.addLast(v) }
        return out
    }

    /**
     * The app's own icon at full brightness. If it can't be loaded, the notification's status-bar
     * icon tinted in the app's LED colour; never a text letter.
     */
    private fun iconChip(pm: PackageManager, app: NotificationStore.AppInfo): View = ImageView(context).apply {
        isClickable = true
        isFocusable = false
        defaultFocusHighlightEnabled = false // no grey focus square behind the icon
        contentDescription = labelFor(pm, app.pkg)
        val appIcon = iconFor(pm, app.pkg)
        val small = app.smallIcon?.let { runCatching { it.loadDrawable(context) }.getOrNull() }
        when {
            appIcon != null -> setImageDrawable(appIcon)
            small != null -> {
                setImageDrawable(small)
                imageTintList = ColorStateList.valueOf(app.color)
                setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
            }
            else -> background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(app.color)
            }
        }
        setOnClickListener {
            selected = if (selected == app.pkg) null else app.pkg
            applySelection()
            onAppTap?.invoke(app.pkg)
        }
    }

    /** The tapped app lifts slightly. Nothing is dimmed. */
    private fun applySelection() {
        chips.forEach { (pkg, v) ->
            v.animate().translationY(if (selected == pkg) -dp(4f).toFloat() else 0f).setDuration(180).start()
        }
    }

    fun clearSelection() {
        selected = null
        applySelection()
    }

    /** Entrance: drop in with a slight overshoot; icons pop in from the middle outwards. */
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
        val mid = icons.childCount / 2
        for (i in 0 until icons.childCount) {
            icons.getChildAt(i).animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(60L + kotlin.math.abs(i - mid) * 70L).setDuration(320)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }
    }

    /**
     * Before a droplet falls: show the strip with the newest icon hidden (it pops out of the drop);
     * the other icons fade in meanwhile.
     */
    fun prepareDrop() {
        if (icons.childCount == 0) return
        animate().cancel()
        visibility = VISIBLE
        alpha = 1f; translationY = 0f; scaleX = 1f; scaleY = 1f
        val newest = newestPkg?.let { chips[it] }
        for (i in 0 until icons.childCount) {
            val c = icons.getChildAt(i)
            c.animate().cancel()
            if (c === newest) {
                c.alpha = 0f; c.scaleX = 0.25f; c.scaleY = 0.25f
            } else if (!shown) {
                c.alpha = 0f; c.scaleX = 0.6f; c.scaleY = 0.6f
                c.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(150L + i * 60L).setDuration(300).start()
            }
        }
        shown = true
    }

    /** The drop has landed: the newest app's icon pops out of it. */
    fun popNewest() {
        val c = newestPkg?.let { chips[it] } ?: return
        c.animate().cancel()
        c.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(380)
            .setInterpolator(OvershootInterpolator(2.4f)).start()
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
        private const val ICON_DP = 36f
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

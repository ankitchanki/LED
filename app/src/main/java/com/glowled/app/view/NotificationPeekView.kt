package com.glowled.app.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.glowled.app.core.NotificationContent
import com.glowled.app.core.NotificationStore

/**
 * Samsung AOD-style notification peek under the app icons: pitch black (no box, so AMOLED pixels
 * stay off), soft dimmed text. Shows the app + time, who it's from, the latest messages and a
 * media label (Photo / Video / Voice message / Document). Tap it to open the chat.
 */
class NotificationPeekView(context: Context) : LinearLayout(context) {

    var onOpen: ((NotificationStore.Entry) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()
    private var current: NotificationStore.Entry? = null
    private var shown = false

    // Dim greys on black, like an always-on display: readable without lighting the panel up.
    private fun grey(alpha: Float) = Color.argb((255 * alpha).toInt(), 255, 255, 255)

    private val appIcon = ImageView(context).apply { alpha = 0.6f }
    private val appName = TextView(context).apply {
        setTextColor(grey(0.42f))
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
    }
    private val title = TextView(context).apply {
        setTextColor(grey(0.72f))
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val body = TextView(context).apply {
        setTextColor(grey(0.55f))
        textSize = 14f
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.12f)
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val media = TextView(context).apply {
        setTextColor(grey(0.6f))
        textSize = 12f
        setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
    }
    private val more = TextView(context).apply {
        setTextColor(grey(0.35f))
        textSize = 12f
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
        setBackgroundColor(Color.BLACK)
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            addView(appIcon, LayoutParams(dp(16f), dp(16f)))
            addView(appName, LayoutParams(-2, -2).apply { marginStart = dp(6f) })
        }
        addView(head, LayoutParams(-2, -2))
        addView(title, LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        addView(body, LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        addView(media, LayoutParams(-2, -2).apply { topMargin = dp(8f) })
        addView(more, LayoutParams(-2, -2).apply { topMargin = dp(8f) })
        alpha = 0f
        visibility = INVISIBLE
        isClickable = true
        setOnClickListener { current?.let { onOpen?.invoke(it) } }
    }

    /** Fills the peek with the newest notification of an app (+ how many more). */
    fun bind(entries: List<NotificationStore.Entry>) {
        val e = entries.firstOrNull() ?: return
        current = e
        val pm = context.packageManager
        val icon = AppInfoView.iconFor(pm, e.pkg)
        appIcon.setImageDrawable(icon)
        appIcon.visibility = if (icon == null) GONE else VISIBLE
        val ago = DateUtils.getRelativeTimeSpanString(e.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        appName.text = "${AppInfoView.labelFor(pm, e.pkg)} · $ago"
        if (e.hidden) {
            title.text = "New notification"
            body.text = "Content hidden"
            body.visibility = VISIBLE
            media.visibility = GONE
        } else {
            title.text = e.title ?: AppInfoView.labelFor(pm, e.pkg)
            val text = e.lines.joinToString("\n")
            body.text = text
            body.visibility = if (text.isBlank()) GONE else VISIBLE
            val label = NotificationContent.label(e.kind)
            media.text = label
            media.visibility = if (label.isEmpty()) GONE else VISIBLE
            // Thin outline in the app's colour (no fill, keeps pixels off).
            media.background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(Color.BLACK)
                setStroke(dp(1f), (e.color and 0x00FFFFFF) or 0x66000000)
            }
        }
        val extra = entries.size - 1
        more.text = if (extra > 0) "+$extra more" else ""
        more.visibility = if (extra > 0) VISIBLE else GONE
    }

    fun isShowing() = shown

    fun show() {
        animate().cancel()
        visibility = VISIBLE
        if (!shown) {
            alpha = 0f
            translationY = -dp(12f).toFloat()
        }
        shown = true
        animate().alpha(1f).translationY(0f)
            .setDuration(320).setInterpolator(OvershootInterpolator(1f)).start()
    }

    fun hide() {
        if (!shown) return
        shown = false
        animate().cancel()
        animate().alpha(0f).translationY(-dp(8f).toFloat()).setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!shown) visibility = INVISIBLE }.start()
    }
}
